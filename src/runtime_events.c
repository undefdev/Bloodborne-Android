/* SPDX-License-Identifier: GPL-2.0-or-later
 * Haptics event log (Android, CUSA03173 v1.09, native code only). With BB_EVENT_LOG set (a file path), hook slots in the
 * game's sound and rumble code append one line per event to that file, all on one clock, to design controller haptics
 * from: which sounds the characters' animations play, which sound events the game starts, and which rumbles run with
 * which motor values. The sites come from a static analysis of the 1.09 eboot; each hook is set only when the bytes
 * at its site match (logged per hook as "Runtime: event log hook <name> installed / skipped: ...").
 *
 * A line is "<ms> <kind> <fields>", ms = CLOCK_BOOTTIME in milliseconds (Android's SystemClock.elapsedRealtime()):
 *   S1 <letter><9-digit id> raw=<type>/<id> chr=P|O ptr=<chr>   TAE 128 sound about to play: final category letter and id
 *                                                              (material-adjusted), the event's raw SoundType/SoundID; P is
 *                                                              the player character, O any other
 *   S2 <letter><9-digit id> raw=<type>/<id> chr=P|O ptr=<chr>   TAE 130 / 10130 voice sound, final category and id
 *   SND <name>                                                 a sound event the game creates, by name (at most 96 chars)
 *   R1+ <id> el=<s> dur=<s> pos=0|1                            a rumble (camera shake + padRumble.rmb record <id>) is
 *                                                              applied from this frame on: elapsed and duration (s), 1 for
 *                                                              a positional (distance falloff) entry
 *   R1- <id> last=<ms>                                         it is no longer applied; last = the last frame it was
 *   R2 <id> chr=P|O ptr=<chr>                                  TAE 145 InvokeRumbleCam_Global queued
 *   R3 <id> chr=P|O ptr=<chr> dp=<dummypoly> fall=<start>/<end> TAE 144 InvokeRumbleCam_Local requested
 *   R4 <heavy> <light>                                         motor values 0..1 the game sets (after the vibration
 *                                                              option), when either two-decimal value changes
 *   PV <large> <small>                                         scePadSetVibration bytes, when they change
 *   START, HOOK <name> <status>, DROP <lines>, CAP             bookkeeping
 * Lines are appended under a mutex to an in-memory buffer; a writer thread appends it to the file (opened once, O_APPEND,
 * no fsync) every 250 ms and soon after each R/PV line. The game threads never wait for the file. The file stops at 64 MiB. */
#define _GNU_SOURCE
#include "runtime.h"
#ifdef __ANDROID__
#include "guest_cpu.h"
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define WORLD_CHR_MAN 0x0553e878u /* *(image+this)+0x60: the player character (as runtime_pad.c's footstep hook) */
#define BUFFER_BYTES (512u*1024u)
#define CAP_BYTES (64ull<<20)
#define FLUSH_MS 250
#define RUMBLE_STALE_MS 150 /* an R1 entry not seen for this long is no longer applied */
#define RUMBLES 64
#define NAME_MAX_CHARS 96

static unsigned char *image_base;
static int log_fd=-1;
/* lock: the buffer and the change tracking below; write_lock: the file (one writer at a time, in order). */
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER, write_lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t wake;
static char buffers[2][BUFFER_BYTES];
static char *buffer=buffers[0];
static size_t used;
static int urgent, capped;
static unsigned long long total; /* bytes accepted for the file, its size at open included */
static unsigned long dropped;    /* lines lost to a full buffer since the last DROP line */

static unsigned long long now_ms(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_BOOTTIME,&t)) return 0;
    return (unsigned long long)t.tv_sec*1000u+(unsigned long long)t.tv_nsec/1000000u;
}

/* With lock held: appends "<ms> <text>\n". The time is taken here, so the file's lines are in time order. */
static void append_locked(const char *text, size_t length) {
    if (capped) return;
    char stamp[24];
    const int s=snprintf(stamp,sizeof(stamp),"%llu ",now_ms());
    if (s<0) return;
    static const char cap_text[]="CAP 64 MiB reached, logging stopped\n";
    const size_t need=(size_t)s+length+1, reserve=(size_t)s+sizeof(cap_text);
    if (total+need+reserve>CAP_BYTES) {
        capped=1;
        if (used+reserve<=BUFFER_BYTES) {
            memcpy(buffer+used,stamp,(size_t)s); memcpy(buffer+used+s,cap_text,sizeof(cap_text)-1);
            used+=reserve-1; total+=reserve-1;
        }
        urgent=1; pthread_cond_signal(&wake);
        return;
    }
    if (used+need>BUFFER_BYTES) { ++dropped; urgent=1; pthread_cond_signal(&wake); return; }
    memcpy(buffer+used,stamp,(size_t)s); used+=(size_t)s;
    memcpy(buffer+used,text,length); used+=length;
    buffer[used++]='\n';
    total+=need;
}

__attribute__((format(printf,1,0)))
static void vformat_locked(const char *format, va_list args) {
    char text[224];
    int n=vsnprintf(text,sizeof(text),format,args);
    if (n<0) return;
    if ((size_t)n>=sizeof(text)) n=(int)sizeof(text)-1;
    append_locked(text,(size_t)n);
}
__attribute__((format(printf,1,2)))
static void format_locked(const char *format, ...) {
    va_list args; va_start(args,format); vformat_locked(format,args); va_end(args);
}
/* A line from any thread; `flush`: the writer writes it out now rather than within 250 ms. */
__attribute__((format(printf,2,3)))
static void emit(int flush, const char *format, ...) {
    va_list args; va_start(args,format);
    pthread_mutex_lock(&lock);
    vformat_locked(format,args);
    if (flush) { urgent=1; pthread_cond_signal(&wake); }
    pthread_mutex_unlock(&lock);
    va_end(args);
}

/* ---- R1: the rumble entries applied per frame, by queue slot ---- */
typedef struct { uint64_t slot; int32_t id; float elapsed; unsigned long long seen; } Rumble;
static Rumble rumbles[RUMBLES];
static int rumble_count;

static void rumble_end_locked(int i) {
    format_locked("R1- %d last=%llu",rumbles[i].id,rumbles[i].seen);
    rumbles[i]=rumbles[--rumble_count];
    urgent=1; pthread_cond_signal(&wake);
}
static void rumble_sweep_locked(unsigned long long ms) {
    for (int i=0;i<rumble_count;) {
        if (ms-rumbles[i].seen>RUMBLE_STALE_MS) rumble_end_locked(i); else ++i;
    }
}

/* Writes what the buffer holds (the writer thread, and at exit). */
static void flush_file(void) {
    pthread_mutex_lock(&write_lock);
    pthread_mutex_lock(&lock);
    char *out=buffer;
    const size_t n=used;
    buffer=out==buffers[0] ? buffers[1] : buffers[0];
    used=0; urgent=0;
    if (dropped) { format_locked("DROP %lu",dropped); dropped=0; }
    pthread_mutex_unlock(&lock);
    for (size_t done=0;done<n;) {
        const ssize_t w=write(log_fd,out+done,n-done);
        if (w<0 && errno==EINTR) continue;
        if (w<=0) break;
        done+=(size_t)w;
    }
    pthread_mutex_unlock(&write_lock);
}

static void *writer(void *arg) {
    (void)arg;
    for (;;) {
        pthread_mutex_lock(&lock);
        struct timespec until;
        clock_gettime(CLOCK_MONOTONIC,&until);
        until.tv_nsec+=FLUSH_MS*1000000L;
        if (until.tv_nsec>=1000000000L) { until.tv_nsec-=1000000000L; ++until.tv_sec; }
        while (!urgent && pthread_cond_timedwait(&wake,&lock,&until)!=ETIMEDOUT) {}
        rumble_sweep_locked(now_ms()); /* the last entries end even when no rumble runs any more */
        pthread_mutex_unlock(&lock);
        flush_file();
    }
    return NULL;
}

/* ---- The hooks (game threads: read registers and memory only, never change them) ---- */
static int is_player(uint64_t chr) {
    const unsigned char *world=*(const unsigned char *const *)(image_base+WORLD_CHR_MAN);
    return chr && world && *(const uint64_t *)(world+0x60)==chr;
}
static char letter(uint64_t category) {
    static const char letters[]="acfopsmvxbgyz"; /* the game's SoundType letters (0x4730c90) */
    return (uint32_t)category<sizeof(letters)-1 ? letters[(uint32_t)category] : '?';
}
static int32_t read32(uint64_t address) { int32_t v; memcpy(&v,(const void *)(uintptr_t)address,4); return v; }
static float xmm_float(const GuestRegsEx *regs, int n) { float v; memcpy(&v,regs->ymm[n],4); return v; }

static void sound(const char *kind, uint64_t chr, uint64_t category, uint64_t id, uint64_t params) {
    const int32_t raw_type=params ? read32(params) : -1, raw_id=params ? read32(params+4) : -1;
    emit(0,"%s %c%09d raw=%d/%d chr=%c ptr=%llx",kind,letter(category),(int32_t)id,raw_type,raw_id,
         is_player(chr) ? 'P' : 'O',(unsigned long long)chr);
}
/* S1, 0x1a26526 (TAE 128, after the trigger, chr-type and material checks): rax character, ecx final category,
 * ebx final id, r14 params ([0] SoundType, [4] SoundID). */
static uint32_t hook_s1(void *context, GuestRegsEx *regs) {
    (void)context;
    sound("S1",regs->gpr[GUEST_RAX],regs->gpr[GUEST_RCX],regs->gpr[GUEST_RBX],regs->gpr[GUEST_R14]);
    return GUEST_HOOK_CONTINUE;
}
/* S2, 0x1a26c68 (TAE 130 / 10130 helper, before the slot branch): r13 character, r12d final category, ebx final id,
 * rdi params ([0] SoundType, [4] SoundID). */
static uint32_t hook_s2(void *context, GuestRegsEx *regs) {
    (void)context;
    sound("S2",regs->gpr[GUEST_R13],regs->gpr[GUEST_R12],regs->gpr[GUEST_RBX],regs->gpr[GUEST_RDI]);
    return GUEST_HOOK_CONTINUE;
}
/* The sound system's four "create event" methods (vtable 0x48/0x50/0x58/0x60), at their first instruction after the
 * prologue: rdx = const char *name, which the method copies with strlcpy right after. */
static uint32_t hook_sound_event(void *context, GuestRegsEx *regs) {
    (void)context;
    const char *name=(const char *)(uintptr_t)regs->gpr[GUEST_RDX];
    char text[4+NAME_MAX_CHARS];
    size_t n=4;
    memcpy(text,"SND ",4);
    if (!name) { memcpy(text+n,"(null)",6); n+=6; }
    else for (size_t i=0;i<NAME_MAX_CHARS && name[i];++i) {
        const unsigned char c=(unsigned char)name[i];
        text[n++]=c<0x20 || c==0x7f ? '?' : (char)c;
    }
    pthread_mutex_lock(&lock);
    append_locked(text,n);
    pthread_mutex_unlock(&lock);
    return GUEST_HOOK_CONTINUE;
}
/* R1, 0x17e05f6 (0x17e05d0, once per queued rumble per camera update): rbx = queue slot + 0x10, [rbx+0x30] the
 * table entry ([0] id, [4] duration s), xmm0 = elapsed s (this frame's step included), byte [rbx+0x28] positional.
 * The function skips an entry whose elapsed is past its duration; the queue drops it before the next frame. */
static uint32_t hook_r1(void *context, GuestRegsEx *regs) {
    (void)context;
    const uint64_t slot=regs->gpr[GUEST_RBX];
    const uint64_t entry=*(const uint64_t *)(uintptr_t)(slot+0x30);
    if (!entry) return GUEST_HOOK_CONTINUE;
    const int32_t id=read32(entry);
    float duration;
    memcpy(&duration,(const void *)(uintptr_t)(entry+4),4);
    const float elapsed=xmm_float(regs,0);
    const unsigned positional=*(const unsigned char *)(uintptr_t)(slot+0x28);
    pthread_mutex_lock(&lock);
    const unsigned long long ms=now_ms();
    rumble_sweep_locked(ms);
    int i=0;
    while (i<rumble_count && rumbles[i].slot!=slot) ++i;
    if (i==rumble_count) i=-1;
    if (!(elapsed<=duration)) {
        if (i>=0) rumble_end_locked(i);
    } else {
        /* The slot holds another rumble now (a new id, or the same id started again). */
        if (i>=0 && (rumbles[i].id!=id || elapsed<rumbles[i].elapsed)) { rumble_end_locked(i); i=-1; }
        if (i>=0) { rumbles[i].elapsed=elapsed; rumbles[i].seen=ms; }
        else if (rumble_count<RUMBLES) {
            rumbles[rumble_count++]=(Rumble){slot,id,elapsed,ms};
            format_locked("R1+ %d el=%.3f dur=%.2f pos=%u",id,elapsed,duration,positional ? 1u : 0u);
            urgent=1; pthread_cond_signal(&wake);
        }
    }
    pthread_mutex_unlock(&lock);
    return GUEST_HOOK_CONTINUE;
}
/* R2, 0x1a2582d (TAE 145, after the local-player and on-ground checks, the id found in the rumble table, about to be
 * queued): r13d id, r15 the TAE handler ([r15+0x10] character). */
static uint32_t hook_r2(void *context, GuestRegsEx *regs) {
    (void)context;
    const uint64_t chr=*(const uint64_t *)(uintptr_t)(regs->gpr[GUEST_R15]+0x10);
    emit(1,"R2 %d chr=%c ptr=%llx",(int32_t)regs->gpr[GUEST_R13],is_player(chr) ? 'P' : 'O',(unsigned long long)chr);
    return GUEST_HOOK_CONTINUE;
}
/* R3, 0x1a252be (TAE 144, after the trigger check; it can still be dropped later): rdi character, r14 params:
 * s16 id, s16 dummypoly, f32 falloff start, f32 falloff end. */
static uint32_t hook_r3(void *context, GuestRegsEx *regs) {
    (void)context;
    const uint64_t chr=regs->gpr[GUEST_RDI], params=regs->gpr[GUEST_R14];
    int16_t id, dummypoly;
    float start, end;
    memcpy(&id,(const void *)(uintptr_t)params,2); memcpy(&dummypoly,(const void *)(uintptr_t)(params+2),2);
    memcpy(&start,(const void *)(uintptr_t)(params+4),4); memcpy(&end,(const void *)(uintptr_t)(params+8),4);
    emit(1,"R3 %d chr=%c ptr=%llx dp=%d fall=%.2f/%.2f",id,is_player(chr) ? 'P' : 'O',(unsigned long long)chr,dummypoly,start,end);
    return GUEST_HOOK_CONTINUE;
}
/* R4, 0xf91946 (0xf91940, the pad device's motor setter, while the pad is active): xmm1 heavy (large motor),
 * xmm0 light (small motor), both 0..1 and already scaled by the vibration option. */
static long last_heavy=-1, last_light=-1; /* in hundredths, as logged */
static uint32_t hook_r4(void *context, GuestRegsEx *regs) {
    (void)context;
    const float heavy=xmm_float(regs,1), light=xmm_float(regs,0);
    const long heavy_centi=lrintf(heavy*100.0f), light_centi=lrintf(light*100.0f);
    pthread_mutex_lock(&lock);
    if (heavy_centi!=last_heavy || light_centi!=last_light) {
        last_heavy=heavy_centi; last_light=light_centi;
        format_locked("R4 %.2f %.2f",heavy,light);
        urgent=1; pthread_cond_signal(&wake);
    }
    pthread_mutex_unlock(&lock);
    return GUEST_HOOK_CONTINUE;
}

void runtime_events_vibration(unsigned large, unsigned small) {
    static int last_large=-1, last_small=-1;
    if (log_fd<0) return;
    pthread_mutex_lock(&lock);
    if ((int)large!=last_large || (int)small!=last_small) {
        last_large=(int)large; last_small=(int)small;
        format_locked("PV %u %u",large,small);
        urgent=1; pthread_cond_signal(&wake);
    }
    pthread_mutex_unlock(&lock);
}

typedef struct {
    const char *name;
    uint32_t site;
    uint8_t length;       /* of the instruction at the site (the catalog line's) */
    uint8_t check_length; /* bytes checked from the site on */
    unsigned char check[12];
    GuestHookEx hook;
} Site;
/* tools/android/hooks-events.txt has the same sites. */
static const Site sites[]={
    {"S1",   0x01a26526,4, 8,{0x48,0x8b,0x40,0x58,0x48,0x8b,0x40,0x08},hook_s1},
    {"S2",   0x01a26c68,4, 6,{0x44,0x8b,0x4f,0x08,0x85,0xf6},hook_s2},
    {"SND48",0x027fadde,7,12,{0xc7,0x45,0xb8,0x00,0x00,0x00,0x00,0xe8,0xf6,0x6e,0xfe,0xff},hook_sound_event},
    {"SND50",0x027fb1e0,7,11,{0xc7,0x45,0xb8,0x00,0x00,0x00,0x00,0x49,0x8b,0x45,0x00},hook_sound_event},
    {"SND58",0x027fb6e3,7,12,{0xc7,0x45,0xb8,0x00,0x00,0x00,0x00,0xe8,0xf1,0x65,0xfe,0xff},hook_sound_event},
    {"SND60",0x027fca2c,7,12,{0xc7,0x45,0xb8,0x00,0x00,0x00,0x00,0xe8,0xa8,0x52,0xfe,0xff},hook_sound_event},
    {"R1",   0x017e05f6,4, 9,{0x48,0x8b,0x43,0x30,0xc5,0xfa,0x10,0x48,0x04},hook_r1},
    {"R2",   0x01a2582d,3, 9,{0x48,0x01,0xf1,0x0f,0x84,0x5a,0xfa,0xff,0xff},hook_r2},
    {"R3",   0x01a252be,4, 9,{0x41,0x0f,0xbf,0x1e,0x41,0x0f,0xbf,0x46,0x02},hook_r3},
    {"R4",   0x00f91946,4, 9,{0x48,0x8b,0x47,0x10,0xc5,0xfa,0x11,0x48,0x14},hook_r4},
};

void runtime_events_install(unsigned char *image, size_t size) {
    const char *path=getenv("BB_EVENT_LOG");
    if (!path || !*path) return;
    if (size<WORLD_CHR_MAN+sizeof(uint64_t)) { puts("Runtime: event log: unexpected image size, off"); fflush(stdout); return; }
    const int fd=open(path,O_WRONLY|O_CREAT|O_APPEND|O_CLOEXEC,0644);
    if (fd<0) { printf("Runtime: event log: cannot open %s: %s\n",path,strerror(errno)); fflush(stdout); return; }
    struct stat st;
    if (fstat(fd,&st)==0 && st.st_size>0) total=(unsigned long long)st.st_size;
    pthread_condattr_t attributes;
    pthread_condattr_init(&attributes);
    pthread_condattr_setclock(&attributes,CLOCK_MONOTONIC);
    pthread_cond_init(&wake,&attributes);
    pthread_condattr_destroy(&attributes);
    image_base=image;
    log_fd=fd;
    pthread_t thread;
    if (pthread_create(&thread,NULL,writer,NULL)!=0) {
        log_fd=-1; close(fd);
        puts("Runtime: event log: no writer thread, off"); fflush(stdout);
        return;
    }
    pthread_detach(thread);
    atexit(flush_file);
    emit(0,"START event log 1 pid=%d",(int)getpid());
    int installed=0;
    for (size_t i=0;i<sizeof(sites)/sizeof(sites[0]);++i) {
        const Site *s=&sites[i];
        const uintptr_t address=(uintptr_t)(image+s->site);
        const char *status="installed";
        if (s->site+s->check_length>size || memcmp(image+s->site,s->check,s->check_length)) status="skipped: signature mismatch";
        else if (!guest_cpu_has_hook_slot(address)) status="skipped: no hook slot (native code built without hooks-events.txt)";
        else if (!guest_cpu_hook_slot(address,s->length,s->hook,NULL)) status="skipped: hook slot refused";
        else ++installed;
        printf("Runtime: event log hook %s %s\n",s->name,status);
        emit(0,"HOOK %s %s",s->name,status);
    }
    printf("Runtime: event log: %d of %zu hooks installed, writing %s\n",installed,sizeof(sites)/sizeof(sites[0]),path);
    fflush(stdout);
}
#endif
