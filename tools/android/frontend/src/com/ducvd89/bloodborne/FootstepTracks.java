package com.ducvd89.bloodborne;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Kishi footstep tracks made from the game's own footstep sounds (tools/android/footstep_haptics.py writes the game folder's
 *  haptics/footsteps.txt): floor material -> surface, and per surface and gait tracks of amplitude (0-63) and frequency
 *  index (0-127) pairs, one pair per 7.5 ms slot. No Android classes (tested on a desktop JDK). */
final class FootstepTracks {
    static final String FILE="haptics/footsteps.txt";
    private static final int MAX_SLOTS=64;
    private final Map<Integer,String> surfaces=new HashMap<>();
    private final Map<String,List<int[]>> tracks=new HashMap<>();
    private final Random random=new Random();
    private int[] last;

    /** The game folder's tracks, or null when it has none (or they do not parse). */
    static FootstepTracks load(File gameDir) {
        File file=new File(gameDir,FILE);
        if (!file.isFile()) return null;
        try (BufferedReader in=new BufferedReader(new FileReader(file))) { return parse(in); }
        catch (IOException | RuntimeException e) { return null; }
    }

    static FootstepTracks parse(BufferedReader in) throws IOException {
        FootstepTracks out=new FootstepTracks();
        for (String line; (line=in.readLine())!=null;) {
            String[] part=line.trim().split("\\s+");
            if (part[0].equals("m") && part.length==3) out.surfaces.put(Integer.parseInt(part[1]),part[2]);
            else if (part[0].equals("t") && part.length>3 && part.length-3<=MAX_SLOTS) {
                int[] track=new int[(part.length-3)*2];
                for (int i=3;i<part.length;i++) {
                    String[] pair=part[i].split(":");
                    track[(i-3)*2]=Math.max(0,Math.min(63,Integer.parseInt(pair[0])));
                    track[(i-3)*2+1]=Math.max(0,Math.min(127,Integer.parseInt(pair[1])));
                }
                out.tracks.computeIfAbsent(part[1]+" "+part[2],k -> new ArrayList<>()).add(track);
            }
        }
        if (out.tracks.isEmpty()) throw new IOException("no tracks");
        return out;
    }

    /** A track for a step on this material (another one than last time when it can), or null for an unknown material. */
    int[] pick(int material,boolean running) {
        String surface=surfaces.get(material);
        if (surface==null) return null;
        List<int[]> choices=tracks.get(surface+(running ? " run" : " walk"));
        if (choices==null) choices=tracks.get(surface+(running ? " walk" : " run"));
        if (choices==null) return null;
        int[] track=choices.get(random.nextInt(choices.size()));
        if (track==last && choices.size()>1) track=choices.get((choices.indexOf(track)+1+random.nextInt(choices.size()-1))%choices.size());
        return last=track;
    }
}
