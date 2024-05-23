package bgu.spl.net.impl.tftp;

import bgu.spl.net.api.MessageEncoderDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class TftpEncoderDecoder implements MessageEncoderDecoder<byte[]> {
   
    private byte[] bytes = new byte[1 << 10]; //start with 1k
    private int len = 0;
    private boolean endWithZero = true;

    @Override
    public byte[] decodeNextByte(byte nextByte) {
        if (len == 0 ) {
            pushByte(nextByte);
        }
        else if (len == 1) {
                if (nextByte == 3 || nextByte == 4 ){
                    endWithZero = false;
                    pushByte(nextByte);// need to take care of transfer!
                }
                else if(nextByte == 6 ||nextByte == 10){
                    pushByte(nextByte);
                     return popBytes();
                }
                else{
                    pushByte(nextByte);
                }
        }
        else {
            if (endWithZero) {
                if (nextByte == 0) {
                    return popBytes(); // Return the message
                } else {
                    pushByte(nextByte);
                }  
            } 
            else {
                if(bytes[1] == 3){
                    if(len < 3){
                        pushByte(nextByte); 
                    }
                    else{
                        int packetSize = (((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF));      
                        if (len == (packetSize + 6) - 1) {
                            pushByte(nextByte);
                            return popBytes();
    
                        }
                        else{
                            pushByte(nextByte);
                        }    
                    }    
                }
                else if (bytes[1] == 4) {
                    if (len < 3) {
                        pushByte(nextByte);
                    } 
                    else {
                        pushByte(nextByte);
                        return popBytes();
                    }
                } 
            }
        }
    return null; // Not yet complete
}

    @Override
    public byte[] encode(byte[] message) {                   //need to impliment
        return message;
        
    }

    private void pushByte(byte nextByte) {
        if (len >= bytes.length) {
           bytes = Arrays.copyOf(bytes, len * 2); 
        }

        bytes[len++] = nextByte;
    }

    private byte[] popBytes() {
        byte[] bytesToProcess = new byte[len];
        for(int i = 0 ; i < len ; i++){
            bytesToProcess[i] = bytes[i];
        }
        len = 0;
        endWithZero = true;
        return bytesToProcess;
    }


}