package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import java.io.*;
import java.nio.*;
import java.util.*;
import static io.github.rajami1205.filesystem.application.RuntimeWire.*;

record Bootstrap(DiskGeometry geometry,String volume,char[] password,char[] confirmation) implements AutoCloseable {
    public void close() { Arrays.fill(password,'\0'); Arrays.fill(confirmation,'\0'); }
    static Bootstrap read(InputStream in) throws IOException {
        byte[] bytes=in.readNBytes(4097);
        try {
            var b=ByteBuffer.wrap(bytes);
            if(bytes.length>4096 || bytes.length<24 || b.getInt()!=0x4f534642 || b.getShort()!=1) throw new Protocol();
            var g=new DiskGeometry(b.getLong(),b.getInt());
            String volume=text(b,64);
            char[] password=chars(b,512);
            try {
                char[] confirm=chars(b,512);
                try { end(b); return new Bootstrap(g,volume,password,confirm); }
                catch(IOException e) { Arrays.fill(confirm,'\0'); throw e; }
            } catch(IOException e) { Arrays.fill(password,'\0'); throw e; }
        } finally { Arrays.fill(bytes,(byte)0); }
    }
    void write(OutputStream out) throws IOException {
        var b=ByteBuffer.allocate(4096);
        try {
            b.putInt(0x4f534642).putShort((short)1).putLong(geometry.diskBytes()).putInt(geometry.blockSize());
            putText(b,CharBuffer.wrap(volume),64); putText(b,CharBuffer.wrap(password),512);
            putText(b,CharBuffer.wrap(confirmation),512);
            out.write(b.array(),0,b.position()); out.flush();
        } finally { Arrays.fill(b.array(),(byte)0); }
    }
    static final UUID ZERO=new UUID(0,0);
    record Result(int code, UUID disk, UUID instance) {
        void write(OutputStream out) throws IOException {
            var b=ByteBuffer.allocate(39).putInt(0x4f534652).putShort((short)1).put((byte)code);
            uuid(b,disk); uuid(b,instance); out.write(b.array()); out.flush();
        }
        static Result read(InputStream in) throws IOException {
            byte[] bytes=in.readNBytes(40);
            if(bytes.length!=39) throw new Protocol();
            var b=ByteBuffer.wrap(bytes);
            if(b.getInt()!=0x4f534652 || b.getShort()!=1) throw new Protocol();
            int code=b.get(); UUID disk=uuid(b), instance=uuid(b);
            if(code<0 || code>4 || ((code==0 || code==4) ?
                    (disk.equals(ZERO)||instance.equals(ZERO)): (!disk.equals(ZERO)||!instance.equals(ZERO)))) throw new Protocol();
            return new Result(code,disk,instance);
        }
    }
}
