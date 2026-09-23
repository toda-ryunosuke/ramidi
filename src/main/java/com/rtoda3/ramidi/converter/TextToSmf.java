package com.rtoda3.ramidi.converter;

import com.rtoda3.ramidi.core.RamidiException;
import com.rtoda3.ramidi.core.RamidiInstruction;
import com.rtoda3.ramidi.support.MessageResolver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Track;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 純粋に変換のみのクラス
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TextToSmf {

    private static final int MIDI_RESOLUTION = 480; // PPQ (Ticks per Quarter Note)
    private static final int MIDI_TRACK_LIMIT = 128;

    private final MessageResolver messageResolver;

    public byte[] assemble(List<RamidiInstruction> instructions) throws IOException {
        try {
            var sequence = new Sequence(Sequence.PPQ, MIDI_RESOLUTION);
            var tracks = new Track[MIDI_TRACK_LIMIT];

            for (var instruction : instructions) {
                try {
                    addEventFromInstruction(instruction, sequence, tracks);
                } catch (Exception e) {
                    var msg = messageResolver.getMessage("error.smf.conversion.failed");
                    throw new RamidiException(msg, instruction, e);
                }
            }

            try (var outputStream = new ByteArrayOutputStream()) {
                MidiSystem.write(sequence, 1, outputStream);
                return outputStream.toByteArray();
            }

        } catch (InvalidMidiDataException e) {
            var msg = messageResolver.getMessage("error.smf.structure.invalid");
            throw new RamidiException(msg, e);
        }


    }

    private void addEventFromInstruction(RamidiInstruction instruction, Sequence sequence,
        Track[] tracks) throws InvalidMidiDataException {
        var command = instruction.command();
        var args = instruction.args();

        switch (command) {
            // --- Channel Voice Messages ---
            case "NOTE" -> addNoteEvent(args, sequence, tracks);
            case "CC" -> addControlChangeEvent(args, sequence, tracks);
            case "PROGRAM" -> addProgramChangeEvent(args, sequence, tracks);
            case "BEND" -> addPitchBendEvent(args, sequence, tracks);
            case "POLY_PRESS" -> addPolyPressureEvent(args, sequence, tracks);
            case "CHAN_PRESS" -> addChannelPressureEvent(args, sequence, tracks);

            // --- Meta Messages (Timing & Structure) ---
            case "TEMPO" -> addTempoEvent(args, sequence, tracks);
            case "TIMESIG" -> addTimeSignatureEvent(args, sequence, tracks);
            case "KEYSIG" -> addKeySignatureEvent(args, sequence, tracks);
            case "META_TEXT" -> addMetaTextEvent(args, sequence, tracks);

            // --- System Exclusive ---
            case "SYSEX" -> addSysexEvent(args, sequence, tracks);

            // --- その他 ---
            default -> {
                var msg = messageResolver.getMessage("error.smf.command.unknown", command);
                throw new RamidiException(msg, instruction);
            }
        }
    }

    private void addNoteEvent(List<String> p, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var track = getOrCreateTrack(seq, trks, Integer.parseInt(p.get(0)));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, Integer.parseInt(p.get(1)),
            Integer.parseInt(p.get(3)), Integer.parseInt(p.get(4))), Long.parseLong(p.get(2))));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, Integer.parseInt(p.get(1)),
            Integer.parseInt(p.get(3)), 0), Long.parseLong(p.get(2)) + Long.parseLong(p.get(5))));
    }

    private void addControlChangeEvent(List<String> p, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var track = getOrCreateTrack(seq, trks, Integer.parseInt(p.get(0)));
        track.add(new MidiEvent(
            new ShortMessage(ShortMessage.CONTROL_CHANGE, Integer.parseInt(p.get(1)),
                Integer.parseInt(p.get(3)), Integer.parseInt(p.get(4))), Long.parseLong(p.get(2))));
    }

    private void addProgramChangeEvent(List<String> p, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var track = getOrCreateTrack(seq, trks, Integer.parseInt(p.get(0)));
        track.add(new MidiEvent(
            new ShortMessage(ShortMessage.PROGRAM_CHANGE, Integer.parseInt(p.get(1)),
                Integer.parseInt(p.get(3)), 0), Long.parseLong(p.get(2))));
    }

    private void addPitchBendEvent(List<String> p, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var val = Integer.parseInt(p.get(3)) + 8192;
        val = Math.clamp(val, 0, 16383);
        var track = getOrCreateTrack(seq, trks, Integer.parseInt(p.get(0)));
        track.add(new MidiEvent(
            new ShortMessage(ShortMessage.PITCH_BEND, Integer.parseInt(p.get(1)), val & 0x7F,
                (val >> 7) & 0x7F), Long.parseLong(p.get(2))));
    }

    private void addPolyPressureEvent(List<String> p, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var track = getOrCreateTrack(seq, trks, Integer.parseInt(p.get(0)));
        track.add(new MidiEvent(
            new ShortMessage(ShortMessage.POLY_PRESSURE, Integer.parseInt(p.get(1)),
                Integer.parseInt(p.get(3)), Integer.parseInt(p.get(4))), Long.parseLong(p.get(2))));
    }

    private void addChannelPressureEvent(List<String> p, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var track = getOrCreateTrack(seq, trks, Integer.parseInt(p.get(0)));
        track.add(new MidiEvent(
            new ShortMessage(ShortMessage.CHANNEL_PRESSURE, Integer.parseInt(p.get(1)),
                Integer.parseInt(p.get(3)), 0), Long.parseLong(p.get(2))));
    }

    private void addTempoEvent(List<String> p, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var mpqn = (int) Math.round(60_000_000.0 / Double.parseDouble(p.get(2)));
        var data = new byte[]{(byte) ((mpqn >> 16) & 0xFF), (byte) ((mpqn >> 8) & 0xFF),
            (byte) (mpqn & 0xFF)};
        getOrCreateTrack(seq, trks, Integer.parseInt(p.get(0))).add(
            new MidiEvent(new MetaMessage(0x51, data, 3), Long.parseLong(p.get(1))));
    }

    private void addTimeSignatureEvent(List<String> p, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var data = new byte[]{(byte) Integer.parseInt(p.get(2)),
            (byte) (Math.log(Integer.parseInt(p.get(3))) / Math.log(2)), 24, 8};
        getOrCreateTrack(seq, trks, Integer.parseInt(p.get(0))).add(
            new MidiEvent(new MetaMessage(0x58, data, 4), Long.parseLong(p.get(1))));
    }

    private void addKeySignatureEvent(List<String> p, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var data = new byte[]{(byte) Integer.parseInt(p.get(2)), (byte) Integer.parseInt(p.get(3))};
        getOrCreateTrack(seq, trks, Integer.parseInt(p.get(0))).add(
            new MidiEvent(new MetaMessage(0x59, data, 2), Long.parseLong(p.get(1))));
    }

    private void addMetaTextEvent(List<String> p, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var textBytes = p.get(3).getBytes(StandardCharsets.UTF_8);
        getOrCreateTrack(seq, trks, Integer.parseInt(p.get(0))).add(
            new MidiEvent(new MetaMessage(Integer.parseInt(p.get(2)), textBytes, textBytes.length),
                Long.parseLong(p.get(1))));
    }

    private void addSysexEvent(List<String> p, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var hexTokens = p.get(2).split("\\s+");
        var sysexBytes = new byte[hexTokens.length];
        for (int i = 0; i < hexTokens.length; i++) {
            sysexBytes[i] = (byte) Integer.parseInt(hexTokens[i], 16);
        }
        getOrCreateTrack(seq, trks, Integer.parseInt(p.get(0))).add(
            new MidiEvent(new SysexMessage(sysexBytes, sysexBytes.length),
                Long.parseLong(p.get(1))));
    }

    private Track getOrCreateTrack(Sequence sequence, Track[] tracks, int trkNum) {
        if (tracks[trkNum] == null) {
            tracks[trkNum] = sequence.createTrack();
        }
        return tracks[trkNum];
    }
}