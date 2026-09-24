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

    // Meta Message Types
    private static final int META_TEMPO = 0x51;
    private static final int META_TIME_SIGNATURE = 0x58;
    private static final int META_KEY_SIGNATURE = 0x59;

    private final MessageResolver messageResolver;

    public byte[] assemble(List<RamidiInstruction> instructions) {
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

        } catch (InvalidMidiDataException | IOException e) {
            var msg = messageResolver.getMessage("error.smf.structure.invalid");
            throw new RamidiException(msg, e);
        }
    }

    private void addEventFromInstruction(RamidiInstruction instruction, Sequence sequence,
        Track[] tracks) throws InvalidMidiDataException {
        var command = instruction.command();

        switch (command) {
            // --- Channel Voice Messages ---
            case "NOTE" -> addNoteEvent(instruction, sequence, tracks);
            case "CC" -> addControlChangeEvent(instruction, sequence, tracks);
            case "PROGRAM" -> addProgramChangeEvent(instruction, sequence, tracks);
            case "BEND" -> addPitchBendEvent(instruction, sequence, tracks);
            case "POLY_PRESS" -> addPolyPressureEvent(instruction, sequence, tracks);
            case "CHAN_PRESS" -> addChannelPressureEvent(instruction, sequence, tracks);

            // --- Meta Messages (Timing & Structure) ---
            case "TEMPO" -> addTempoEvent(instruction, sequence, tracks);
            case "TIMESIG" -> addTimeSignatureEvent(instruction, sequence, tracks);
            case "KEYSIG" -> addKeySignatureEvent(instruction, sequence, tracks);
            case "META_TEXT" -> addMetaTextEvent(instruction, sequence, tracks);

            // --- System Exclusive ---
            case "SYSEX" -> addSysexEvent(instruction, sequence, tracks);

            // --- その他 ---
            default -> {
                var msg = messageResolver.getMessage("error.smf.command.unknown", command);
                throw new RamidiException(msg, instruction);
            }
        }
    }

    private void addNoteEvent(RamidiInstruction instruction, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var track = getOrCreateTrack(seq, trks, instruction.getIntArg(0), instruction);
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, instruction.getIntArg(1),
            instruction.getIntArg(3), instruction.getIntArg(4)), instruction.getLongArg(2)));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, instruction.getIntArg(1),
            instruction.getIntArg(3), 0), instruction.getLongArg(2) + instruction.getLongArg(5)));
    }

    private void addControlChangeEvent(RamidiInstruction instruction, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var track = getOrCreateTrack(seq, trks, instruction.getIntArg(0), instruction);
        track.add(new MidiEvent(
            new ShortMessage(ShortMessage.CONTROL_CHANGE, instruction.getIntArg(1),
                instruction.getIntArg(3), instruction.getIntArg(4)), instruction.getLongArg(2)));
    }

    private void addProgramChangeEvent(RamidiInstruction instruction, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var track = getOrCreateTrack(seq, trks, instruction.getIntArg(0), instruction);
        track.add(new MidiEvent(
            new ShortMessage(ShortMessage.PROGRAM_CHANGE, instruction.getIntArg(1),
                instruction.getIntArg(3), 0), instruction.getLongArg(2)));
    }

    private void addPitchBendEvent(RamidiInstruction instruction, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var val = instruction.getIntArg(3) + 8192;
        val = Math.clamp(val, 0, 16383);
        var track = getOrCreateTrack(seq, trks, instruction.getIntArg(0), instruction);
        track.add(new MidiEvent(
            new ShortMessage(ShortMessage.PITCH_BEND, instruction.getIntArg(1), val & 0x7F,
                (val >> 7) & 0x7F), instruction.getLongArg(2)));
    }

    private void addPolyPressureEvent(RamidiInstruction instruction, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var track = getOrCreateTrack(seq, trks, instruction.getIntArg(0), instruction);
        track.add(new MidiEvent(
            new ShortMessage(ShortMessage.POLY_PRESSURE, instruction.getIntArg(1),
                instruction.getIntArg(3), instruction.getIntArg(4)), instruction.getLongArg(2)));
    }

    private void addChannelPressureEvent(RamidiInstruction instruction, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var track = getOrCreateTrack(seq, trks, instruction.getIntArg(0), instruction);
        track.add(new MidiEvent(
            new ShortMessage(ShortMessage.CHANNEL_PRESSURE, instruction.getIntArg(1),
                instruction.getIntArg(3), 0), instruction.getLongArg(2)));
    }

    private void addTempoEvent(RamidiInstruction instruction, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var mpqn = (int) Math.round(60_000_000.0 / instruction.getDoubleArg(2));
        var data = new byte[]{(byte) ((mpqn >> 16) & 0xFF), (byte) ((mpqn >> 8) & 0xFF),
            (byte) (mpqn & 0xFF)};
        getOrCreateTrack(seq, trks, instruction.getIntArg(0), instruction).add(
            new MidiEvent(new MetaMessage(META_TEMPO, data, 3), instruction.getLongArg(1)));
    }

    private void addTimeSignatureEvent(RamidiInstruction instruction, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var denominator = instruction.getIntArg(3);
        if (Integer.bitCount(denominator) != 1) {
            var msg = messageResolver.getMessage("error.smf.timesig.denominator.invalid",
                denominator);
            throw new RamidiException(msg, instruction);
        }
        var denomPower = (byte) Integer.numberOfTrailingZeros(denominator);
        var data = new byte[]{(byte) instruction.getIntArg(2), denomPower, 24, 8};
        getOrCreateTrack(seq, trks, instruction.getIntArg(0), instruction).add(
            new MidiEvent(new MetaMessage(META_TIME_SIGNATURE, data, 4),
                instruction.getLongArg(1)));
    }

    private void addKeySignatureEvent(RamidiInstruction instruction, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var data = new byte[]{(byte) instruction.getIntArg(2), (byte) instruction.getIntArg(3)};
        getOrCreateTrack(seq, trks, instruction.getIntArg(0), instruction).add(
            new MidiEvent(new MetaMessage(META_KEY_SIGNATURE, data, 2),
                instruction.getLongArg(1)));
    }

    private void addMetaTextEvent(RamidiInstruction instruction, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var textBytes = instruction.getStringArg(3).getBytes(StandardCharsets.UTF_8);
        getOrCreateTrack(seq, trks, instruction.getIntArg(0), instruction).add(
            new MidiEvent(new MetaMessage(instruction.getIntArg(2), textBytes, textBytes.length),
                instruction.getLongArg(1)));
    }

    private void addSysexEvent(RamidiInstruction instruction, Sequence seq, Track[] trks)
        throws InvalidMidiDataException {
        var hexString = instruction.getStringArg(2);
        if (hexString.isBlank()) {
            var msg = messageResolver.getMessage("error.smf.sysex.data.empty");
            throw new RamidiException(msg, instruction);
        }
        var hexTokens = hexString.trim().split("\\s+");
        var sysexBytes = new byte[hexTokens.length];
        for (var i = 0; i < hexTokens.length; i++) {
            sysexBytes[i] = (byte) Integer.parseInt(hexTokens[i], 16);
        }
        getOrCreateTrack(seq, trks, instruction.getIntArg(0), instruction).add(
            new MidiEvent(new SysexMessage(sysexBytes, sysexBytes.length),
                instruction.getLongArg(1)));
    }

    private Track getOrCreateTrack(Sequence sequence, Track[] tracks, int trkNum,
        RamidiInstruction instruction) {
        if (trkNum < 0 || trkNum >= MIDI_TRACK_LIMIT) {
            var msg = messageResolver.getMessage("error.smf.track.limit.exceeded", trkNum,
                MIDI_TRACK_LIMIT - 1);
            throw new RamidiException(msg, instruction);
        }
        if (tracks[trkNum] == null) {
            tracks[trkNum] = sequence.createTrack();
        }
        return tracks[trkNum];
    }
}
