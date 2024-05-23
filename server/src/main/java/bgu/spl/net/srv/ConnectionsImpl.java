package bgu.spl.net.srv;


import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionsImpl <T> implements Connections <T > {
    
    private ConcurrentHashMap<Integer, ConnectionHandler<T>> activeClients;

    public ConnectionsImpl() {
        activeClients = new ConcurrentHashMap<>();
    }

    public void connect(int connectionId, ConnectionHandler<T> handler){
        activeClients.put(connectionId, handler);
    }

    public boolean send(int connectionId, T msg){
        ConnectionHandler<T> handler = activeClients.get(connectionId);
        if (handler != null) {
            handler.send(msg);
            return true;
        } else {
            return false; // Connection not found
        }
    }

    public void disconnect(int connectionId){
        ConnectionHandler<T> handler = activeClients.get(connectionId);
        activeClients.remove(connectionId);
        try{
            handler.close();
        }
        catch(IOException ex){};// need to check!
    }
}
