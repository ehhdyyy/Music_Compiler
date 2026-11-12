import javax.sound.midi.*;

public class VML {
  static class MusicEvent {
  }
  static class Rest extends MusicEvent {
    int time;
  }
  static class Note extends MusicEvent {
    int key;
    int duration;
    public Note(int key, int duration) {
      if (key >= 0 && key <= 88)
        this.key = key;
      else throw new IllegalArgumentException("A piano note is between 0 and 88");
      this.duration = duration;
    }
    public int getKey() {
      return key;
    }
    public int getDuration() {
      return duration;
    }
  }
  static class Chord extends MusicEvent{
    Note[] notes;
    public Chord(Note[] notes) {
      this.notes = notes;
    }
    public Note[] getNotes() {
      return notes;
    }
  }
  static class Measure {
    MusicEvent[] musicEvents;
    int tempo;
    public Measure(MusicEvent[] musicEvents, int tempo) {
      this.musicEvents = musicEvents;
      this.tempo = tempo;
    }
    public MusicEvent[] getMusicEvents() {
      return musicEvents;
    }
    public int getTempo() {
      return tempo;
    }
  }
  // static class Track {
  //   Measure[] measures;
  //   public Track(Measure[] measures) {
  //     this.measures = measures;
  //   }
  // }
  // static class Song {
  //   Track[] tracks;
  //   public Song(Track[] tracks) {
  //     this.tracks = tracks;
  //   }
  // }

  public static void main(String[] args) {
    Note c4 = new Note(40, 16); //these can all be enummerated
    Note f4 = new Note(45, 16); 
    Note g4 = new Note(47, 16);
    Chord Csus4 = new Chord(new Note[] {c4, f4, g4}); //common ones can also be ennumerated
    Measure measure = new Measure(new MusicEvent[]{c4, f4, g4, Csus4}, 60);


    try {
        // 1. Get a Sequencer and open it
        Sequencer sequencer = MidiSystem.getSequencer();
        sequencer.open();

        // 2. Create a Sequence (PPQ = Pulses Per Quarter Note, resolution 4)
        Sequence sequence = new Sequence(Sequence.PPQ, 4);

        // 3. Create a Track in the Sequence
        Track track = sequence.createTrack();

        int currentTick = 1;
        for (MusicEvent e : measure.getMusicEvents()) {
          int velocity = 65 + (int)(Math.random() * 62); // Audible range: 65 to 126

          
          if (e instanceof Note n) {
            ShortMessage on = new ShortMessage();
            ShortMessage off = new ShortMessage();
            on.setMessage(ShortMessage.NOTE_ON, 1, n.getKey() + 20, velocity);
            MidiEvent noteOn = new MidiEvent(on, currentTick);
            track.add(noteOn);

            off.setMessage(ShortMessage.NOTE_OFF, 1, n.getKey() + 20, 0);
            MidiEvent noteOff = new MidiEvent(off, currentTick+=n.getDuration());
            track.add(noteOff);            
          }

          else if (e instanceof Chord c) {
            for (Note n : c.getNotes()) {
              int pitch = n.getKey() + 20;
              ShortMessage on = new ShortMessage();
              ShortMessage off = new ShortMessage();

              // Press each key down at tick 1
              on.setMessage(ShortMessage.NOTE_ON, 1, pitch, velocity);
              track.add(new MidiEvent(on, currentTick));

              // Release each key at tick 16s
              off.setMessage(ShortMessage.NOTE_OFF, 1, pitch, 0);
              track.add(new MidiEvent(off, currentTick));
            }
            currentTick += c.getNotes()[0].getDuration();
          }
        }

        // 6. Load sequence into sequencer and start playing
        sequencer.setSequence(sequence);
        sequencer.setTempoInBPM(measure.getTempo());
        sequencer.start();

        // Wait until the entire measure finishes playing
        while (sequencer.isRunning()) {
            Thread.sleep(100);
        }
        Thread.sleep(500);
        sequencer.close();

    } 
    catch (Exception ex) {
        ex.printStackTrace();
    }
  }
}
