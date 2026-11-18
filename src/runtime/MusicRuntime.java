package runtime;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Synthesizer;

/**
 * Runtime library called by compiled .mus programs.
 * Pitches are MIDI note numbers (60 = middle C); durations are in beats.
 * If no MIDI synthesizer is available, notes are still printed and timed.
 */
public class MusicRuntime
{
    private static Synthesizer synth;
    private static MidiChannel channel;
    private static int bpm = 120;
    private static int velocity = 90;
    private static boolean started = false;

    private static void init()
    {
        if (started) return;
        started = true;
        try {
            synth = MidiSystem.getSynthesizer();
            synth.open();
            channel = synth.getChannels()[0];
        } catch (Exception e) {
            System.err.println("[MusicRuntime] No MIDI synthesizer, running silent: " + e.getMessage());
        }
    }

    private static void sleepBeats(int beats)
    {
        try { Thread.sleep(Math.max(0, beats) * 60_000L / bpm); }
        catch (InterruptedException ignored) {}
    }

    public static void tempo(int newBpm)
    {
        bpm = Math.max(1, newBpm);
        System.out.println("tempo " + bpm);
    }

    public static void play(int pitch, int beats)
    {
        init();
        pitch = Math.max(0, Math.min(127, pitch));
        System.out.println("play " + noteName(pitch) + " (" + pitch + ") for " + beats + " beat(s)");
        if (channel != null) channel.noteOn(pitch, velocity);
        sleepBeats(beats);
        if (channel != null) channel.noteOff(pitch);
    }

    /** Chord qualities: semitone offsets from the root, indexed by the quality code. */
    private static final int[][] CHORDS = {
        {0, 4, 7},      // 0 major
        {0, 3, 7},      // 1 minor
        {0, 3, 6},      // 2 diminished
        {0, 4, 8},      // 3 augmented
        {0, 4, 7, 10},  // 4 dominant 7th
        {0, 4, 7, 11},  // 5 major 7th
        {0, 3, 7, 10},  // 6 minor 7th
    };
    private static final String[] CHORD_NAMES = {"maj", "min", "dim", "aug", "dom7", "maj7", "min7"};

    /** Play all notes of a chord together. Unknown quality codes fall back to major. */
    public static void chord(int root, int quality, int beats)
    {
        init();
        if (quality < 0 || quality >= CHORDS.length) quality = 0;
        int[] offsets = CHORDS[quality];
        int[] notes = new int[offsets.length];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < offsets.length; i++) {
            notes[i] = Math.max(0, Math.min(127, root + offsets[i]));
            sb.append(i == 0 ? "" : " ").append(noteName(notes[i]));
        }
        root = Math.max(0, Math.min(127, root));
        System.out.println("chord " + noteName(root) + CHORD_NAMES[quality]
                           + " [" + sb + "] for " + beats + " beat(s)");
        if (channel != null) for (int n : notes) channel.noteOn(n, velocity);
        sleepBeats(beats);
        if (channel != null) for (int n : notes) channel.noteOff(n);
    }

    public static void rest(int beats)
    {
        init();
        System.out.println("rest " + beats + " beat(s)");
        sleepBeats(beats);
    }

    public static void instrument(int program)
    {
        init();
        if (channel != null) channel.programChange(Math.max(0, Math.min(127, program)));
    }

    public static void volume(int v) { velocity = Math.max(0, Math.min(127, v)); }

    public static void print(int value) { System.out.println(value); }

    /** Called at the end of main: let the last note ring out, then close. */
    public static void finish()
    {
        if (synth != null) {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            synth.close();
        }
    }

    private static final String[] NAMES = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};

    private static String noteName(int pitch) { return NAMES[pitch % 12] + (pitch / 12 - 1); }
}
