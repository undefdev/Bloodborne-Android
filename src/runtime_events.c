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
 *   W 0|1                                                      the player character is in a world (1) or not (0: the
 *                                                              title and its menus, a loading screen), when it changes
 *                                                              (runtime_atlas.c's position read, only with the event pipe)
 *   START, HOOK <name> <status>, DROP <lines>, CAP             bookkeeping
 * Lines are appended under a mutex to an in-memory buffer; a writer thread appends it to the file (opened once, O_APPEND,
 * no fsync) every 250 ms and soon after each R/PV line. The game threads never wait for the file. The file stops at 64 MiB.
 *
 * Rumble file. With BB_RUMBLE_FILE set (the app's "<large> <small>\n", 0-255), hook R4 is set also without BB_EVENT_LOG,
 * and while it is installed it drives that file instead of scePadSetVibration (runtime_pad.c): the game sends the light
 * motor to scePadSetVibration as a PWM bit (0/1 per frame, 0xf95be0), so the app would only see it on or off, while R4
 * has both motors' values before that. They are converted as the game converts the heavy one (clamped to 0..1, times
 * 255, truncated, so large matches scePadSetVibration's byte). R4 runs only while the pad is active (0xf91940 returns
 * early otherwise, and the per-frame sender then sends zeros), so when it has not run for MOTOR_STALE_MS the file is set
 * to 0 0, or to the bytes of scePadSetVibration calls made since then (small 0 or 255). The game threads only note the
 * values; a thread writes the file (tmp + rename) when what it should hold changes.
 *
 * Event pipe. With BB_EVENT_PIPE set (a FIFO the app made and reads), hooks S1, S2 and R1 are set also without
 * BB_EVENT_LOG (the event log works as before), and the app gets one line per event, ms as above:
 *   <ms> H <hook>...      first, once: the pipe's hooks that are installed (of S1 S2 R1), e.g. "H S1 S2 R1"
 *   <ms> S <letter><9-digit id>   a sound of the player character (S1 or S2, as in the log's lines)
 *   <ms> R+ <id> / <ms> R- <id>   a rumble starts / ends (as the log's R1+ / R1- lines)
 *   <ms> W 0|1                    the player character left / entered a world (as the log's W lines): the app makes the
 *                                 left stick the d-pad while it is 0
 * The game threads only queue a line into a ring buffer under a mutex (a full ring drops the line); a writer thread
 * writes whole lines to the FIFO, opened O_WRONLY|O_NONBLOCK: with no reader yet (ENXIO) the open is tried again
 * every PIPE_RETRY_MS and the queued lines wait; a full pipe (EAGAIN) drops the lines; after EPIPE (the reader went
 * away) it is opened again. SIGPIPE is blocked in that thread. Rumbles are swept from there too, so their R- comes
 * without the log's writer. */
#define _GNU_SOURCE
#include "runtime.h"
#ifdef __ANDROID__
#include "guest_cpu.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <math.h>
#include <pthread.h>
#include <signal.h>
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
#define MOTOR_STALE_MS 250 /* rumble file: a source not called for this long no longer counts */
#define MOTOR_RETRY_MS 250 /* rumble file: a failed write is tried again after this at the latest */
#define PIPE_RING_BYTES (16u*1024u)
#define PIPE_LINE_MAX 64   /* one event pipe line, "\n" included */
#define PIPE_CHUNK (PIPE_BUF<4096 ? PIPE_BUF : 4096) /* a pipe write up to PIPE_BUF is all or nothing */
#define PIPE_RETRY_MS 500  /* event pipe: open again after ENXIO (no reader yet) or a failure */
#define PIPE_SWEEP_MS 100  /* event pipe: rumbles are swept this often while one runs */

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
static void monotonic_cond(pthread_cond_t *cond) {
    pthread_condattr_t attributes;
    pthread_condattr_init(&attributes);
    pthread_condattr_setclock(&attributes,CLOCK_MONOTONIC);
    pthread_cond_init(cond,&attributes);
    pthread_condattr_destroy(&attributes);
}

/* With lock held: the log's writer writes out soon (nothing without the log). */
static void log_wake_locked(void) {
    if (log_fd<0) return;
    urgent=1; pthread_cond_signal(&wake);
}

/* With lock held: appends "<ms> <text>\n" (nothing without the log). The time is taken here, so the file's lines
 * are in time order. */
static void append_locked(const char *text, size_t length) {
    if (log_fd<0 || capped) return;
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
    if (flush) log_wake_locked();
    pthread_mutex_unlock(&lock);
    va_end(args);
}

/* ---- The event pipe's ring (BB_EVENT_PIPE, see the top). pipe_lock may be taken with lock held, never the reverse. ---- */
static pthread_mutex_t pipe_lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t pipe_wake;
static char pipe_ring[PIPE_RING_BYTES];
static size_t pipe_head, pipe_fill;  /* the oldest queued byte, the bytes queued (whole lines) */
static unsigned long pipe_dropped;   /* lines lost to a full ring, a full pipe or no reader */
static const char *pipe_path;
static int pipe_on;                  /* set before the hooks: the writer runs and the hooks queue lines */

/* Queues "<ms> <text>\n" for the app, from any thread (also with lock held); dropped when the ring is full. */
__attribute__((format(printf,1,2)))
static void pipe_line(const char *format, ...) {
    char line[PIPE_LINE_MAX];
    int n=snprintf(line,sizeof(line),"%llu ",now_ms());
    if (n<0 || (size_t)n>=sizeof(line)) return;
    va_list args; va_start(args,format);
    const int text=vsnprintf(line+n,sizeof(line)-(size_t)n,format,args);
    va_end(args);
    if (text<0 || (size_t)(n+text)>sizeof(line)-1) return; /* never a cut line: room for "\n" */
    n+=text;
    line[n++]='\n';
    pthread_mutex_lock(&pipe_lock);
    if (pipe_fill+(size_t)n>PIPE_RING_BYTES) ++pipe_dropped;
    else {
        size_t at=(pipe_head+pipe_fill)%PIPE_RING_BYTES;
        for (int i=0;i<n;++i,at=(at+1)%PIPE_RING_BYTES) pipe_ring[at]=line[i];
        pipe_fill+=(size_t)n;
        pthread_cond_signal(&pipe_wake);
    }
    pthread_mutex_unlock(&pipe_lock);
}

/* ---- R1: the rumble entries applied per frame, by queue slot ---- */
typedef struct { uint64_t slot; int32_t id; float elapsed; unsigned long long seen; } Rumble;
static Rumble rumbles[RUMBLES];
static int rumble_count;

static void rumble_end_locked(int i) {
    format_locked("R1- %d last=%llu",rumbles[i].id,rumbles[i].seen);
    if (pipe_on) pipe_line("R- %d",rumbles[i].id);
    rumbles[i]=rumbles[--rumble_count];
    log_wake_locked();
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

/* ---- The rumble file (BB_RUMBLE_FILE) from R4's motor values, see the top ---- */
typedef struct { int large, small; unsigned long long seen; } Motors; /* 0..255 each; seen: now_ms, 0 never */
static pthread_mutex_t motor_lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t motor_wake;
static const char *rumble_path;
static int rumble_from_r4;              /* set before the game runs */
static Motors r4_motors, pv_motors;     /* hook R4, scePadSetVibration */
static int written_large=-1, written_small=-1;

/* As 0xf95be0 converts the heavy motor: below 0 (or NaN) 0, above 1 255, else times 255 truncated. */
static int motor_byte(float value) { return !(value>0.0f) ? 0 : value>1.0f ? 255 : (int)(value*255.0f); }
static int motors_fresh(const Motors *m, unsigned long long ms) { return m->seen && (long long)(ms-m->seen)<=MOTOR_STALE_MS; }

/* With motor_lock held: what the file should hold at ms (R4's values; else scePadSetVibration's, from calls after R4
 * went stale: in the frames where R4 ran they are its PWM bits; else 0 0), and when that changes without a new call
 * (0: never, it is 0 0). */
static void motor_target(unsigned long long ms, int *large, int *small, unsigned long long *again) {
    const Motors *m=NULL;
    if (motors_fresh(&r4_motors,ms)) m=&r4_motors;
    else if (motors_fresh(&pv_motors,ms) && pv_motors.seen>r4_motors.seen+MOTOR_STALE_MS) m=&pv_motors;
    *large=m ? m->large : 0;
    *small=m ? m->small : 0;
    *again=*large || *small ? m->seen+MOTOR_STALE_MS+1 : 0;
}

/* A game thread's values; the writer is woken when the file should change. */
static void motors_set(Motors *m, int large, int small) {
    pthread_mutex_lock(&motor_lock);
    const unsigned long long ms=now_ms();
    m->large=large; m->small=small; m->seen=ms;
    int want_large, want_small;
    unsigned long long again;
    motor_target(ms,&want_large,&want_small,&again);
    if (want_large!=written_large || want_small!=written_small) pthread_cond_signal(&motor_wake);
    pthread_mutex_unlock(&motor_lock);
}

static int rumble_write(int large, int small) {
    char tmp[512];
    if (snprintf(tmp,sizeof(tmp),"%s.tmp",rumble_path)>=(int)sizeof(tmp)) return 0;
    FILE *f=fopen(tmp,"w");
    if (!f) return 0;
    fprintf(f,"%d %d\n",large,small);
    return fclose(f)==0 && rename(tmp,rumble_path)==0;
}

static void *motor_writer(void *arg) {
    (void)arg;
    pthread_mutex_lock(&motor_lock);
    for (;;) {
        const unsigned long long ms=now_ms();
        int large, small;
        unsigned long long again;
        motor_target(ms,&large,&small,&again);
        if (large!=written_large || small!=written_small) {
            pthread_mutex_unlock(&motor_lock);
            const int written=rumble_write(large,small);
            pthread_mutex_lock(&motor_lock);
            if (written) { written_large=large; written_small=small; continue; }
            again=ms+MOTOR_RETRY_MS;
        }
        if (!again) { pthread_cond_wait(&motor_wake,&motor_lock); continue; }
        const unsigned long long wait=again>ms ? again-ms : 1;
        struct timespec until;
        clock_gettime(CLOCK_MONOTONIC,&until);
        until.tv_sec+=(time_t)(wait/1000u);
        until.tv_nsec+=(long)(wait%1000u)*1000000L;
        if (until.tv_nsec>=1000000000L) { until.tv_nsec-=1000000000L; ++until.tv_sec; }
        pthread_cond_timedwait(&motor_wake,&motor_lock,&until);
    }
    return NULL;
}

/* After the hooks are set: R4 drives the file when it is installed (hooked) and the writer runs. */
static void rumble_start(const char *path, int hooked, const char *status) {
    if (!hooked) { printf("Runtime: rumble file from scePadSetVibration (hook R4 %s)\n",status); return; }
    rumble_path=path;
    monotonic_cond(&motor_wake);
    pthread_t thread;
    if (pthread_create(&thread,NULL,motor_writer,NULL)!=0) {
        puts("Runtime: rumble file from scePadSetVibration (hook R4: no writer thread)");
        return;
    }
    pthread_detach(thread);
    rumble_from_r4=1;
    puts("Runtime: rumble file from hook R4 (the game's motor values)");
}

/* ---- The event pipe's writer (BB_EVENT_PIPE, see the top) ---- */
static void pipe_report(const char *what) {
    printf("Runtime: event pipe %s %s\n",pipe_path,what);
    fflush(stdout);
}

/* SIGPIPE is blocked in this thread: a write after the reader went away is EPIPE, and the signal it left pending (Linux
 * sends it to the writing thread) is taken here, so it never reaches the process. */
static void pipe_take_sigpipe(void) {
    sigset_t pending, only;
    if (sigpending(&pending) || !sigismember(&pending,SIGPIPE)) return;
    sigemptyset(&only); sigaddset(&only,SIGPIPE);
    int signal_number;
    sigwait(&only,&signal_number);
}

static void *pipe_writer(void *arg) {
    (void)arg;
    sigset_t blocked;
    sigemptyset(&blocked); sigaddset(&blocked,SIGPIPE);
    pthread_sigmask(SIG_BLOCK,&blocked,NULL);
    int fd=-1, reported=0, ever_open=0;
    unsigned long long retry_at=0;
    static char chunk[PIPE_CHUNK];
    for (;;) {
        unsigned long long ms=now_ms();
        if (fd<0 && ms>=retry_at) {
            fd=open(pipe_path,O_WRONLY|O_NONBLOCK|O_CLOEXEC);
            struct stat st;
            if (fd>=0 && (fstat(fd,&st) || !S_ISFIFO(st.st_mode))) { close(fd); fd=-1; errno=EINVAL; }
#ifdef F_SETNOSIGPIPE
            if (fd>=0) fcntl(fd,F_SETNOSIGPIPE,1); /* macOS (host tests): there SIGPIPE goes to the process, not the thread */
#endif
            if (fd>=0) {
                if (!ever_open) pipe_report("open");
                ever_open=1;
            } else {
                retry_at=ms+PIPE_RETRY_MS;
                if (errno!=ENXIO && !reported) {
                    reported=1;
                    char what[96];
                    snprintf(what,sizeof(what),"cannot be opened (%s), trying again",errno==EINVAL ? "not a FIFO" : strerror(errno));
                    pipe_report(what);
                }
            }
        }
        /* The last rumbles end (R-) also when no R1 call follows. */
        pthread_mutex_lock(&lock);
        rumble_sweep_locked(ms);
        const int sweeping=rumble_count>0;
        pthread_mutex_unlock(&lock);

        pthread_mutex_lock(&pipe_lock);
        int timed_out=0;
        while (!timed_out && (!pipe_fill || fd<0)) {
            /* Lines wait while there is no reader; the wait ends for the next open or sweep. */
            unsigned long long wait=0;
            if (fd<0) wait=retry_at>ms ? retry_at-ms : 1;
            else if (sweeping) wait=PIPE_SWEEP_MS;
            if (!wait) { pthread_cond_wait(&pipe_wake,&pipe_lock); continue; }
            struct timespec until;
            clock_gettime(CLOCK_MONOTONIC,&until);
            until.tv_sec+=(time_t)(wait/1000u);
            until.tv_nsec+=(long)(wait%1000u)*1000000L;
            if (until.tv_nsec>=1000000000L) { until.tv_nsec-=1000000000L; ++until.tv_sec; }
            timed_out=pthread_cond_timedwait(&pipe_wake,&pipe_lock,&until)==ETIMEDOUT;
            ms=now_ms();
        }
        if (fd<0 || !pipe_fill) { pthread_mutex_unlock(&pipe_lock); continue; }
        /* Whole lines, at most PIPE_CHUNK bytes: the write is all or nothing. */
        size_t n=0, whole=0;
        while (n<pipe_fill && n<sizeof(chunk)) {
            const char c=pipe_ring[(pipe_head+n)%PIPE_RING_BYTES];
            chunk[n++]=c;
            if (c=='\n') whole=n;
        }
        if (!whole) whole=n; /* cannot happen: lines are shorter than a chunk */
        unsigned long lines=0;
        for (size_t i=0;i<whole;++i) lines+=chunk[i]=='\n';
        pipe_head=(pipe_head+whole)%PIPE_RING_BYTES;
        pipe_fill-=whole;
        pthread_mutex_unlock(&pipe_lock);
        ssize_t w;
        do w=write(fd,chunk,whole); while (w<0 && errno==EINTR);
        if (w==(ssize_t)whole) continue;
        if (w<0 && errno==EPIPE) {
            pipe_take_sigpipe();
            close(fd); fd=-1; retry_at=0; /* the reader went away: open again now, then every PIPE_RETRY_MS */
            pipe_report("lost its reader, opening it again");
        } else if (w<0 && errno!=EAGAIN) {
            close(fd); fd=-1; retry_at=now_ms()+PIPE_RETRY_MS;
        }
        pthread_mutex_lock(&pipe_lock);
        pipe_dropped+=lines; /* EAGAIN (the pipe is full), or the reader went away: these lines are lost */
        pthread_mutex_unlock(&pipe_lock);
    }
    return NULL;
}

/* The event pipe's writer: 1 when it runs (pipe_on is then set, before any hook). */
static int pipe_start(const char *path) {
    pipe_path=path;
    monotonic_cond(&pipe_wake);
    pthread_t thread;
    if (pthread_create(&thread,NULL,pipe_writer,NULL)!=0) {
        printf("Runtime: event pipe %s: no writer thread, off\n",path);
        fflush(stdout);
        return 0;
    }
    pthread_detach(thread);
    pipe_on=1;
    return 1;
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
    const int player=is_player(chr);
    if (pipe_on && player) pipe_line("S %c%09d",letter(category),(int32_t)id);
    if (log_fd<0) return;
    const int32_t raw_type=params ? read32(params) : -1, raw_id=params ? read32(params+4) : -1;
    emit(0,"%s %c%09d raw=%d/%d chr=%c ptr=%llx",kind,letter(category),(int32_t)id,raw_type,raw_id,
         player ? 'P' : 'O',(unsigned long long)chr);
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
            if (pipe_on) pipe_line("R+ %d",id);
            log_wake_locked();
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
 * xmm0 light (small motor), both 0..1 and already scaled by the vibration option. Logged, and the rumble file's source. */
static long last_heavy=-1, last_light=-1; /* in hundredths, as logged */
static uint32_t hook_r4(void *context, GuestRegsEx *regs) {
    (void)context;
    const float heavy=xmm_float(regs,1), light=xmm_float(regs,0);
    if (rumble_from_r4) motors_set(&r4_motors,motor_byte(heavy),motor_byte(light));
    if (log_fd<0) return GUEST_HOOK_CONTINUE;
    const long heavy_centi=lrintf(heavy*100.0f), light_centi=lrintf(light*100.0f);
    pthread_mutex_lock(&lock);
    if (heavy_centi!=last_heavy || light_centi!=last_light) {
        last_heavy=heavy_centi; last_light=light_centi;
        format_locked("R4 %.2f %.2f",heavy,light);
        log_wake_locked();
    }
    pthread_mutex_unlock(&lock);
    return GUEST_HOOK_CONTINUE;
}

int runtime_events_vibration(unsigned large, unsigned small) {
    static int last_large=-1, last_small=-1;
    if (log_fd>=0) {
        pthread_mutex_lock(&lock);
        if ((int)large!=last_large || (int)small!=last_small) {
            last_large=(int)large; last_small=(int)small;
            format_locked("PV %u %u",large,small);
            log_wake_locked();
        }
        pthread_mutex_unlock(&lock);
    }
    if (!rumble_from_r4) return 0;
    motors_set(&pv_motors,large>255u ? 255 : (int)large,small ? 255 : 0);
    return 1;
}

void runtime_events_world(int present) {
    static int last=-1;
    present=present ? 1 : 0;
    if (present==last) return;
    last=present;
    if (log_fd>=0) emit(1,"W %d",present);
    if (pipe_on) pipe_line("W %d",present);
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

/* The event log's file and writer thread: 1 when it runs. */
static int log_start(const char *path, size_t size) {
    if (size<WORLD_CHR_MAN+sizeof(uint64_t)) { puts("Runtime: event log: unexpected image size, off"); fflush(stdout); return 0; }
    const int fd=open(path,O_WRONLY|O_CREAT|O_APPEND|O_CLOEXEC,0644);
    if (fd<0) { printf("Runtime: event log: cannot open %s: %s\n",path,strerror(errno)); fflush(stdout); return 0; }
    struct stat st;
    if (fstat(fd,&st)==0 && st.st_size>0) total=(unsigned long long)st.st_size;
    monotonic_cond(&wake);
    log_fd=fd;
    pthread_t thread;
    if (pthread_create(&thread,NULL,writer,NULL)!=0) {
        log_fd=-1; close(fd);
        puts("Runtime: event log: no writer thread, off"); fflush(stdout);
        return 0;
    }
    pthread_detach(thread);
    atexit(flush_file);
    emit(0,"START event log 1 pid=%d",(int)getpid());
    return 1;
}

void runtime_events_install(unsigned char *image, size_t size) {
    const char *path=getenv("BB_EVENT_LOG"), *rumble=getenv("BB_RUMBLE_FILE"), *pipe=getenv("BB_EVENT_PIPE");
    if (rumble && !*rumble) rumble=NULL;
    if (pipe && !*pipe) pipe=NULL;
    image_base=image;
    const int logging=path && *path && log_start(path,size);
    if (pipe && size<WORLD_CHR_MAN+sizeof(uint64_t)) { puts("Runtime: event pipe: unexpected image size, off"); pipe=NULL; }
    const int piping=pipe && pipe_start(pipe);
    if (!logging && !rumble && !piping) { fflush(stdout); return; }
    int installed=0, r4=0;
    const char *r4_status="not found";
    char piped[16]=""; /* the event pipe's hooks that are installed, for its H line */
    for (size_t i=0;i<sizeof(sites)/sizeof(sites[0]);++i) {
        const Site *s=&sites[i];
        const int motors=s->hook==hook_r4; /* also for the rumble file */
        const int for_pipe=piping && (s->hook==hook_s1 || s->hook==hook_s2 || s->hook==hook_r1); /* also for the pipe */
        if (!logging && !(motors && rumble) && !for_pipe) continue;
        const uintptr_t address=(uintptr_t)(image+s->site);
        const char *status="installed";
        if (s->site+s->check_length>size || memcmp(image+s->site,s->check,s->check_length)) status="skipped: signature mismatch";
        else if (!guest_cpu_has_hook_slot(address)) status="skipped: no hook slot (native code built without hooks-events.txt)";
        else if (!guest_cpu_hook_slot(address,s->length,s->hook,NULL)) status="skipped: hook slot refused";
        else {
            ++installed; r4|=motors;
            if (for_pipe) { strcat(piped," "); strcat(piped,s->name); }
        }
        if (motors) r4_status=status;
        if (for_pipe && !logging) printf("Runtime: event pipe hook %s %s\n",s->name,status);
        if (!logging) continue;
        printf("Runtime: event log hook %s %s\n",s->name,status);
        emit(0,"HOOK %s %s",s->name,status);
    }
    if (logging) printf("Runtime: event log: %d of %zu hooks installed, writing %s\n",installed,sizeof(sites)/sizeof(sites[0]),path);
    if (piping) {
        pipe_line("H%s",piped);
        printf("Runtime: event pipe: hooks%s, writing %s\n",*piped ? piped : " none",pipe);
    }
    if (rumble) rumble_start(rumble,r4,r4_status);
    fflush(stdout);
}
#endif
