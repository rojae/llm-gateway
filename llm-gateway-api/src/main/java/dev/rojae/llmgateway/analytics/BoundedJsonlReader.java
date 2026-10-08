package dev.rojae.llmgateway.analytics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Memory is capped per line; only a newline makes a line eligible for checkpointing. */
final class BoundedJsonlReader {
    record Line(byte[] bytes, long endOffset, boolean oversized) {}
    private final InputStream input;
    private final int maxBytes;
    private long offset;
    private boolean cleanEof;
    boolean cleanEof(){return cleanEof;}
    BoundedJsonlReader(InputStream input,int maxBytes,long offset){this.input=input;this.maxBytes=maxBytes;this.offset=offset;}
    Line next() throws IOException {
        var bytes=new ByteArrayOutputStream(Math.min(maxBytes,8192));
        boolean oversized=false;
        for(int value;(value=input.read())!=-1;) {
            offset++;
            if(value=='\n')return new Line(bytes.toByteArray(),offset,oversized);
            if(bytes.size()<maxBytes)bytes.write(value); else oversized=true;
            if(Thread.currentThread().isInterrupted())throw new IOException("interrupted");
        }
        cleanEof=bytes.size()==0&&!oversized;
        return null;
    }
}
