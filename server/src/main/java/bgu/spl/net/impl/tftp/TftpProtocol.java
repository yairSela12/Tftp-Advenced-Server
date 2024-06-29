package bgu.spl.net.impl.tftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.io.FileInputStream;
import bgu.spl.net.api.BidiMessagingProtocol;
import bgu.spl.net.srv.Connections;
import java.io.File;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

class holder{
    static ConcurrentHashMap<Integer,Boolean> connected_ids = new ConcurrentHashMap<Integer,Boolean>();
    //a threadSafe HashMap that will hold the usernames as byte arrays that were already loggedin for their connectionID
    static ConcurrentHashMap<String, Boolean> loggedInUsernames = new ConcurrentHashMap<String,Boolean>();

}

class mssageError {
    static final String error1 = "File not found - RRQ DELRQ of non-existing file.";
    static final String error2 = "Access violation - File cannot be written, read or deleted.";
    static final String error3 = "Disk full or allocation exceeded  No room in disk.";
    static final String error4 = "Illegal TFTP operation - Unknown Opcode.";
    static final String error5 = "File already exists - File name exists on WRQ.";
    static final String error6 = " User not logged in - Any opcode received before Login completes.";
    static final String error7 = "User already logged in - Login username already connected.";
    static final String error8 = "There's no files !";

}

class filesLock {
    static Object lock = new Object();
}

public class TftpProtocol implements BidiMessagingProtocol<byte[]>  {
    
    private int connectionId;
    private Connections<byte[]> connections;
    private boolean shouldTerminate = false;
    private String userName = null;
    private static final int MAX_PACKET_SIZE = 512;
    private int sizeOfLastBlock ;
    private ArrayList<Byte> outBuffer = new ArrayList<>();
    private String outFileName = "";
    private int numBlockLEFT = 0; // Keep track of how many block numbers left to sent to the client
    private ArrayList<Byte> inBuffer = new ArrayList<>();
    private String inFileName = "";

    @Override
    public void start(int connectionId, Connections<byte[]> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(byte[] message) {
        if(message[1] == 10){
            if(checkIfAlreadyLog()){
                handleDisconnect();
            }
            else{
                sendError(6);
            }

        }                           
        else if(message[1] == 1){
            if(checkIfAlreadyLog()){
                synchronized(filesLock.lock) {
                    handleRRQ(message);
                }
            }
            else{
                sendError(6);
            }    
        }
        else if(message[1] == 2){
            if(checkIfAlreadyLog()){
                handleWRQ(message);
            }
            else{
                sendError(6); 
            }    
        }
        else if(message[1] == 3){
            receiveData(message);
        }
        else if(message[1] == 4){
            handleAck(message);
        }
        else if(message[1] == 6){
            if(checkIfAlreadyLog()){
                sendList();
            }
            else{
                sendError(6); 
            } 
        }
        else if(message[1] == 7){
            handleConnect(message);
        }
        else if(message[1] == 8){
            if(checkIfAlreadyLog()){
                synchronized(filesLock.lock){
                deleteFile(message);
                }
            }
            else{
                sendError(6); 
            } 
        }

        
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    } 



    
    private void handleDisconnect() {
        if (userName != null) {    
            byte[] ackPacket = createAckPacket(0);
            connections.send(connectionId, ackPacket);
            shouldTerminate = true;
            synchronized (holder.connected_ids) {
                holder.loggedInUsernames.remove(userName);
                holder.connected_ids.remove(connectionId);
                connections.disconnect(connectionId);
            }
        }
        else{
            sendError(6);
        }
    }

    private byte[] createAckPacket(int blockNumber) {
        byte[] ackPacket = new byte[4];
        ackPacket[0] = 0; 
        ackPacket[1] = 4; 
        ackPacket[2] = (byte) (blockNumber >> 8);
        ackPacket[3] = (byte) blockNumber;
        return ackPacket;
    }

    private void handleConnect(byte[] message) {
        System.out.println("entering handleConnect");
        userName = new String(message, 2, message.length - 2, StandardCharsets.UTF_8);
        if(!(checkIfAlreadyExist(connectionId , userName))){
            synchronized (holder.connected_ids) {
                holder.connected_ids.put(connectionId, true);
                holder.loggedInUsernames.put(userName, true);
            }
            byte[] ackPacket = createAckPacket(0);
            connections.send(connectionId, ackPacket);
        }
    }

    private boolean checkIfAlreadyExist(int id , String userName) {
        boolean IdAlreadyConneted = false;
        boolean userNameAlreadyConnected = false;
        
        if(holder.connected_ids.get(id) != null){
            IdAlreadyConneted = holder.connected_ids.get(id);
        }
        if(holder.loggedInUsernames.get(userName) != null){
            userNameAlreadyConnected = holder.loggedInUsernames.get(userName);
        }
        
        if(IdAlreadyConneted || userNameAlreadyConnected){
            sendError(7);
            return true ; 
        }
        
        return false;
    }

    private void handleRRQ(byte[] message) {
        String filename = new String(message, 2, message.length - 2, StandardCharsets.UTF_8);
        outFileName = filename;
        if(!checkIfFileExists(filename)){
                sendError(1); // File not found error
                return;
            }
        else{       
            outBuffer.clear();
            Path filePath = Paths.get("Skeleton/server/Files", outFileName);
                    
            try {
                byte[] fileBytes = Files.readAllBytes(filePath);
                for (byte b : fileBytes) {
                    outBuffer.add(b);
                }
                numBlockLEFT = this.outBuffer.size() / MAX_PACKET_SIZE;
                sizeOfLastBlock = this.outBuffer.size() % MAX_PACKET_SIZE;
                numBlockLEFT++;
                        
                sendDataBlock(1);   
            } 
            catch (IOException e) {
                e.printStackTrace();
                sendError(2);
            }
            }  
        
    }

    private void handleWRQ(byte[] message) {
        String filename = new String(message, 2, message.length - 2, StandardCharsets.UTF_8);
        inFileName = filename;
        if(checkIfFileExists(filename)){
            sendError(5); 
            return;
        }
        inBuffer.clear();    
        byte[] ackPacket = createAckPacket(0);
        connections.send(connectionId, ackPacket);
    }
        
    private boolean checkIfFileExists(String filename) {
        Path filePath = Paths.get("Skeleton/server/Files");
        try {
            return Files.list(filePath).anyMatch(path -> path.getFileName().toString().equals(filename));
                        
        } 
        catch (IOException e) {
            e.printStackTrace();
            return false; // Return false in case of any IO exception , in case we got the right theres no need to be a problem
            
        } 
    }

    private void sendError(int error){
        String errorMessage = null;
        if(error == 1){
            errorMessage = mssageError.error1;
        }
        else if (error == 2){
            errorMessage = mssageError.error2;
        }
        else if (error == 3){
            errorMessage = mssageError.error3;
        }
        else if (error == 4){
            errorMessage = mssageError.error4;
        }
        else if (error == 5){
            errorMessage = mssageError.error5;
        }
        else if (error == 6){
            errorMessage = mssageError.error6;
        }
        else if (error == 7){
            errorMessage = mssageError.error7;
        }
        else if (error == 8){
            errorMessage = mssageError.error8;
        }
        
        byte[] errorInBytes = errorMessage.getBytes(StandardCharsets.UTF_8);
        byte[] finalMessage = new byte[errorInBytes.length + 5];
        finalMessage[0] = 0; 
        finalMessage[1] = 5; 
        finalMessage[2] = (byte) (error >> 8);
        finalMessage[3] = (byte) error;
        finalMessage[finalMessage.length - 1] = 0;
        System.arraycopy(errorInBytes, 0 , finalMessage, 4 ,errorInBytes.length );

        connections.send(connectionId, finalMessage);
    }

    private void sendDataBlock(int blockNumber) {
        int len = 0;
        if(numBlockLEFT > 1){
            len = MAX_PACKET_SIZE; 
            byte[] bufferBlock = new byte[len]; 
            int position = (blockNumber - 1) * MAX_PACKET_SIZE ;
            for(int i = position ; i < position + len ; i++){
                bufferBlock[i - position] = outBuffer.get(i);
            }


            byte[] dataPacket = new byte[len + 6];
                dataPacket[0] = 0;
                dataPacket[1] = 3;
                dataPacket[2] = (byte) (len >> 8);
                dataPacket[3] = (byte) len;
                dataPacket[4] = (byte) (blockNumber >> 8);
                dataPacket[5] = (byte) blockNumber;
                System.arraycopy(bufferBlock, 0, dataPacket, 6, len); 
                
                numBlockLEFT--;
                connections.send(connectionId, dataPacket);
                
        }
        else if(numBlockLEFT == 1){
            if(sizeOfLastBlock > 0){
                len = sizeOfLastBlock; 
                byte[] bufferBlock = new byte[len]; 
                int position = (blockNumber - 1) * MAX_PACKET_SIZE ;
                for(int i = position ; i < position + len ; i++){
                    bufferBlock[i - position] = outBuffer.get(i);
                }
                byte[] dataPacket = new byte[6 + len];
                dataPacket[0] = 0;
                dataPacket[1] = 3;
                dataPacket[2] = (byte) (len >> 8);
                dataPacket[3] = (byte) len;
                dataPacket[4] = (byte) (blockNumber >> 8);
                dataPacket[5] = (byte) blockNumber;
                System.arraycopy(bufferBlock, 0, dataPacket, 6, len);
                numBlockLEFT--;
                connections.send(connectionId, dataPacket); 
                
            }
            else{
                byte[] dataPacket = new byte[6];
                dataPacket[0] = 0;
                dataPacket[1] = 3;
                dataPacket[2] = (byte) (len >> 8);
                dataPacket[3] = (byte) len;
                dataPacket[4] = (byte) (blockNumber >> 8);
                dataPacket[5] = (byte) blockNumber;
                numBlockLEFT--;
                connections.send(connectionId, dataPacket); 
                
            }
        }
    }
            

    
    private void sendBcast(String fileName, byte isAdded){
        byte[] fileInBytes = fileName.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[fileInBytes.length + 4];
        packet[0] = 0;
        packet[1] = 9;
        packet[2] = isAdded;
        packet[packet.length - 1] = 0;
        System.arraycopy(fileInBytes, 0 , packet , 3 ,fileInBytes.length );

        synchronized (holder.connected_ids) {
            for (Integer clientId : holder.connected_ids.keySet()) {
                if (holder.connected_ids.get(clientId)) { 
                    connections.send(clientId, packet); 
                }
            }
        }

    }
            
    private void sendList(){
        synchronized(filesLock.lock){
            String path = "Skeleton/server/Files";
            File direPath = new File(path) ;
            String[] filesList = direPath.list();
            if(filesList.length == 0){
                sendError(8);
            }
            else{
                ArrayList<Byte> bytelist = new ArrayList<>();
                for(String fileName : filesList){
                    byte[] fileNameByte = fileName.getBytes(StandardCharsets.UTF_8); 
                    for(byte nextByte : fileNameByte){
                        bytelist.add(nextByte);
                    }
                    bytelist.add((byte)0);
                }
                bytelist.remove(bytelist.size() - 1 );

                outBuffer = bytelist;
                numBlockLEFT = outBuffer.size() / MAX_PACKET_SIZE;
                sizeOfLastBlock = outBuffer.size() % MAX_PACKET_SIZE;
                numBlockLEFT++;
            } 
        }

            sendDataBlock(1);
            
            }
    

    private void handleAck(byte[] ackPackage){
        int blockNumber = ((ackPackage[2] & 0xFF) << 8) | (ackPackage[3] & 0xFF);
        
        if (blockNumber > 0) {
            sendDataBlock(blockNumber + 1);
        } 
    }

    private void deleteFile(byte[] deletePackage){
        String fileName = new String(deletePackage, 2, deletePackage.length - 2, StandardCharsets.UTF_8);
        boolean isExist = checkIfFileExists(fileName);
        
        if(isExist){
            try {
                Path filePath = Paths.get("Skeleton/server/Files", fileName);
                Files.delete(filePath);
                byte[] ackPacket = createAckPacket(0);
                connections.send(connectionId, ackPacket);
                sendBcast(fileName, (byte)0);

            } 
            catch (IOException e) {
                e.printStackTrace();
            }
        } 
            else {
            sendError(1);
        }

    }

    private void receiveData(byte[] dataPackage){
        int packetSize = ((dataPackage[2] & 0xFF) << 8) | (dataPackage[3] & 0xFF);
        int blockNumber = ((dataPackage[4] & 0xFF) << 8) | (dataPackage[5] & 0xFF);
        for (int i = 6; i < dataPackage.length; i++) {
            inBuffer.add(dataPackage[i]);
        }
        if (packetSize == 512) {
            byte[] ackPacket = createAckPacket(blockNumber);
            connections.send(connectionId, ackPacket);    
        }
        else{
            byte[] outByteArray = new byte[inBuffer.size()];
            for (int i = 0; i < inBuffer.size(); i++) {
                outByteArray[i] = inBuffer.get(i);
            }
            try {
                // Specify the directory where you want to create the new file
                Path directoryPath = Paths.get("Skeleton/server/Files"); 
            
                // Create the new file path
                Path filePath = directoryPath.resolve(inFileName);
            
                // Write the contents of the outByteArray to the new file
                Files.write(filePath, outByteArray);
                byte[] ackPacket = createAckPacket(blockNumber);
                connections.send(connectionId, ackPacket); 
                

                System.out.println("File created successfully.");
                sendBcast(inFileName, (byte) 1 );
            } 
            catch (IOException e) {
                // Handle IOException
                e.printStackTrace();
                sendError(2);
            }
        }
    }

    private boolean checkIfAlreadyLog(){
        return (holder.connected_ids.get(connectionId) != null && holder.connected_ids.get(connectionId));
    }

}
        



    

