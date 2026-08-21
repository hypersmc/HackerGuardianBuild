package me.hackerguardian.main.replay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ReplayCodec {

    private ReplayCodec() {}

    // ---------- OUT (encoder) ----------
    public static final class Out {
        private final DataOutputStream out;

        public Out(OutputStream os) {
            this.out = new DataOutputStream(os);
        }

        public void writeByte(int b) throws IOException { out.writeByte(b); }
        public void writeBoolean(boolean v) throws IOException { out.writeBoolean(v); }
        public void writeInt(int v) throws IOException { out.writeInt(v); }
        public void writeLong(long v) throws IOException { out.writeLong(v); }
        public void writeFloat(float v) throws IOException { out.writeFloat(v); }
        public void writeDouble(double v) throws IOException { out.writeDouble(v); }
        public void writeUUID(UUID u) throws IOException { writeLong(u.getMostSignificantBits()); writeLong(u.getLeastSignificantBits()); }
        public void writeVarInt(int value) throws IOException { ReplayCodec.writeVarInt(out, value); }
        public void writeVarLong(long value) throws IOException { ReplayCodec.writeVarLong(out, value); }

        public void writeString(String s, int maxLen) throws IOException {
            if (s == null) s = "";
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxLen) throw new IOException("String too long: " + bytes.length + " > " + maxLen);
            writeVarInt(bytes.length);
            out.write(bytes);
        }

        public void flush() throws IOException { out.flush(); }
    }

    public static byte[] encodeEvent(ReplayEvent ev) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
            Out out = new Out(baos);
            out.writeVarInt(ev.type().ordinal());
            ev.encode(out);
            out.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- IN (decoder) ----------
    public static final class In {
        private final DataInputStream in;

        public In(InputStream is) {
            this.in = new DataInputStream(is);
        }

        public boolean hasMore() throws IOException {
            return in.available() > 0;
        }

        public boolean readBoolean() throws IOException { return in.readBoolean(); }
        public int readInt() throws IOException { return in.readInt(); }
        public long readLong() throws IOException { return in.readLong(); }
        public float readFloat() throws IOException { return in.readFloat(); }
        public double readDouble() throws IOException { return in.readDouble(); }
        public UUID readUUID() throws IOException { long msb = readLong(); long lsb = readLong(); return new UUID(msb, lsb); }
        public int readVarInt() throws IOException { return ReplayCodec.readVarInt(in); }
        public long readVarLong() throws IOException { return ReplayCodec.readVarLong(in); }

        public String readString(int maxLenBytes) throws IOException {
            int len = readVarInt();
            if (len < 0 || len > maxLenBytes) throw new IOException("Bad string length: " + len);
            byte[] b = new byte[len];
            in.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        public byte[] readBytes(int len) throws IOException {
            byte[] b = new byte[len];
            in.readFully(b);
            return b;
        }
    }

    // ---- varint helpers ----
    public static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    public static void writeVarLong(DataOutputStream out, long value) throws IOException {
        while ((value & 0xFFFFFFFFFFFFFF80L) != 0) {
            out.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte((int) (value & 0x7F));
    }

    public static int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));

            numRead++;
            if (numRead > 5) throw new IOException("VarInt too big");
        } while ((read & 0x80) != 0);

        return result;
    }

    public static long readVarLong(DataInputStream in) throws IOException {
        int numRead = 0;
        long result = 0;
        byte read;
        do {
            read = in.readByte();
            long value = (read & 0x7F);
            result |= (value << (7 * numRead));

            numRead++;
            if (numRead > 10) throw new IOException("VarLong too big");
        } while ((read & 0x80) != 0);

        return result;
    }
}