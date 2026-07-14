package com.projectkorra.projectkorra.platform.mc;

public class Note {
    private final int octave;
    private final Tone tone;
    private final boolean sharped;
    public Note(int octave, Tone tone, boolean sharped) {
        this.octave = octave;
        this.tone = tone;
        this.sharped = sharped;
    }

    public static Note sharp(int octave, Tone tone) {
        return new Note(octave, tone, true);
    }

    public int getOctave() {
        return octave;
    }

    public Tone getTone() {
        return tone;
    }

    public boolean isSharped() {
        return sharped;
    }

    public enum Tone {A, B, C, D, E, F, G}
}
