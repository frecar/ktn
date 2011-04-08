/*
 * Created on Oct 27, 2004
 */
package no.ntnu.fp.net.co;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import no.ntnu.fp.net.admin.Log;
import no.ntnu.fp.net.cl.ClException;
import no.ntnu.fp.net.cl.ClSocket;
import no.ntnu.fp.net.cl.KtnDatagram;
import no.ntnu.fp.net.cl.KtnDatagram.Flag;

/**
 * Implementation of the Connection-interface. <br>
 * <br>
 * This class implements the behaviour in the methods specified in the interface
 * {@link Connection} over the unreliable, connectionless network realised in
 * {@link ClSocket}. The base class, {@link AbstractConnection} implements some
 * of the functionality, leaving message passing and error handling to this
 * implementation.
 * 
 */
public class ConnectionImpl extends AbstractConnection {

    private static Map<Integer, Boolean> usedPorts = Collections.synchronizedMap(new HashMap<Integer, Boolean>());

    private String msg;
    private int originalPort;
    
    public ConnectionImpl(int myPort) {
    	this.myPort = myPort;
    	originalPort = myPort;
    	usedPorts.put(myPort, true);
    	myAddress = getIPv4Address();
    }

    private String getIPv4Address() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        }
        catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    /**
     * Establish a connection to a remote location.
     * 
     * @param remoteAddress
     *            - the remote IP-address to connect to
     * @param remotePort
     *            - the remote portnumber to connect to
     * @throws IOException
     *             If there's an I/O error.
     * @throws java.net.SocketTimeoutException
     *             If timeout expires before connection is completed.
     * @see Connection#connect(InetAddress, int)
     */
    public void connect(InetAddress remoteAddress, int remotePort) throws IOException,
            SocketTimeoutException {

    	this.remoteAddress = remoteAddress.getHostAddress();
    	this.remotePort = remotePort;
    
    	KtnDatagram syn = constructInternalPacket(Flag.SYN);
    	
    	try {
    		simplySendPacket(syn);
    		lastDataPacketSent = syn;
    		state = State.SYN_SENT;	
    	}
    	
    	catch (Exception e) {
    		e.printStackTrace();	
    	}
    	
    	KtnDatagram synACK = receiveAck();
    	
    	//CHECK IF VALID
    	if(synACK != null && synACK.getFlag() == Flag.SYN_ACK && synACK.getAck() == syn.getSeq_nr()) {
    		
    		lastValidPacketReceived = synACK;
    		
    		this.remoteAddress = synACK.getSrc_addr();
    		this.remotePort = synACK.getSrc_port();
    		
    		try {
    			Thread.sleep(100);
    		}
    		catch(Exception e) {
    			e.printStackTrace();
    		}
    		
    		sendAck(synACK, false);
    		
    		state = State.ESTABLISHED;
    		
    	}
    	else {
    		//RECONNECT
    		connect(remoteAddress, remotePort);
    	}
    }
    
    /**
     * Listen for, and accept, incoming connections.
     * 
     * @return A new ConnectionImpl-object representing the new connection.
     * @see Connection#accept()
     */
    public Connection accept() throws IOException, SocketTimeoutException {
    	
    	state = State.LISTEN;

    	KtnDatagram syn = null;
    	
    	while(syn == null || syn.getFlag() != Flag.SYN){
    		syn = receivePacket(true);
    	}
    	
    	lastValidPacketReceived= syn;
    	
    	myPort = getFirstUnusedPort(myPort);
    	remoteAddress = syn.getSrc_addr();
    	remotePort = syn.getSrc_port();
    	
    	try {
			Thread.sleep(100);
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		
		sendAck(syn, true);
		state = State.SYN_RCVD;
		KtnDatagram ack;
		ack = receiveAck();
    	
		if(ack != null && ack.getFlag() == Flag.ACK && ack.getSeq_nr() == syn.getSeq_nr()+1) {
			lastValidPacketReceived = ack;
            AbstractConnection connection = (ConnectionImpl) this.clone();
			connection.state = State.ESTABLISHED;
			reset();
			return connection;
		}
		reset();	
		return null;		
    }
    
    /**
     * Send a message from the application.
     * 
     * @param msg
     *            - the String to be sent.
     * @throws ConnectException
     *             If no connection exists.
     * @throws IOException
     *             If no ACK was received.
     * @see AbstractConnection#sendDataPacketWithRetransmit(KtnDatagram)
     * @see no.ntnu.fp.net.co.Connection#send(String)
     */
    public void send(String msg) throws ConnectException, IOException {

    	KtnDatagram packet = constructDataPacket(msg);
    	lastDataPacketSent = packet;
    	KtnDatagram ack = null;
    
        int attempts = 0;
        while(!isCorrectAck(ack)) {
        	
        	if(attempts>50) {
        		  throw new IOException("DataPacket was sent, " +
                  "but did not receive correct ACK");
        	}
        	attempts+=1;
        	
        	ack = sendDataPacketWithRetransmit(packet);
        
        }
        lastValidPacketReceived = ack;
    }

    /**
     * Wait for incoming data.
     * 
     * @return The received data's payload as a String.
     * @see Connection#receive()
     * @see AbstractConnection#receivePacket(boolean)
     * @see AbstractConnection#sendAck(KtnDatagram, boolean)
     */
    public String receive() throws ConnectException, IOException {

    	KtnDatagram packet = null;
    	
    	while(!isValid(packet)) {
    		
    		try {
    			packet = receivePacket(false);
    		}
    		catch(EOFException e) {
    		
                try {
                        Thread.sleep(80);
                } catch (InterruptedException e1) {
                        // TODO Auto-generated catch block
                        e1.printStackTrace();
                }
                
                sendAck(disconnectRequest, false);
                state = State.CLOSE_WAIT;
                throw e;
    		}
    	}
    	
    	lastValidPacketReceived = packet;
    	
    	 try {
             Thread.sleep(80);
    	 } catch (InterruptedException e1) {
             // TODO Auto-generated catch block
             e1.printStackTrace();
    	 }

    	 sendAck(packet, false);
    	 
    	 return (String) packet.getPayload();
    }

    /**
     * Close the connection.
     * 
     * @see Connection#close()
     */
    public void close() throws IOException {
    	if(state == State.ESTABLISHED) {
    		closeClient();
    	}
    	else if(state == State.CLOSE_WAIT) {
    		closeServer();
    	}	
    }
    
    
    

    private void closeServer() throws IOException {
		
    	KtnDatagram fin = constructInternalPacket(Flag.FIN);
    	
    	try {
            Thread.sleep(100);
		} catch (InterruptedException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
    	}
    	
		try {
			simplySendPacket(fin);
			lastDataPacketSent = fin;
			state = State.LAST_ACK;
		}
		catch(ClException e) {
			e.printStackTrace();
		}
		
		KtnDatagram ackReturn;
		
		while( ! isCorrectAck(ackReturn = receiveAck())) {
			if(ackReturn == null) {
				state = State.CLOSED;
				usedPorts.remove(myPort);
				throw new IOException("Did not receive ACK after sending FIN");
			}
		}
	
		state = State.CLOSED;
		usedPorts.remove(myPort);
	}

	private void closeClient() throws IOException {
		
		KtnDatagram finSend = constructInternalPacket(Flag.FIN);
		
		try {
            Thread.sleep(80);
		} catch (InterruptedException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
    	}
		
		try {
			simplySendPacket(finSend);
			lastDataPacketSent = finSend;
			state = State.FIN_WAIT_1;
		}catch(Exception e) {
			e.printStackTrace();
		}

		KtnDatagram ackReturn;

        while( ! isCorrectAck(ackReturn = receiveAck())){
               if(ackReturn == null)
                       throw new IOException("Did not receive ACK after sending FIN");
        }
        
        lastValidPacketReceived = ackReturn;
        state = State.FIN_WAIT_2;
        
        KtnDatagram receivedFin;
        
        while( ! isCorrectFin(receivedFin = receivePacket(true))) {
        	if (receivedFin == null) {
        		throw new IOException("Server did not return FIN");
        	}
        }
        
        lastValidPacketReceived = receivedFin;
        

		try {
            Thread.sleep(80);
		} catch (InterruptedException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
    	}
		
		sendAck(receivedFin, false);
		
		state = State.TIME_WAIT;
        
	}

	/**
     * Test a packet for transmission errors. This function should only called
     * with data or ACK packets in the ESTABLISHED state.
     * 
     * @param packet
     *            Packet to test.
     * @return true if packet is free of errors, false otherwise.
     */
    protected boolean isValid(KtnDatagram packet) {

        if(packet == null)
            return false;

        boolean valid = true;

        if(packet.getChecksum() != packet.calculateChecksum())
            valid = false;
        else if(packet.getSeq_nr() != lastValidPacketReceived.getSeq_nr() + 1)
            valid = false;
        else if(!packet.getSrc_addr().equals(remoteAddress))
            valid = false;
        else if(packet.getSrc_port() != remotePort)
            valid = false;
        else if(!(packet.getFlag() == Flag.ACK || packet.getFlag() == Flag.NONE))
            valid = false;

        if(!valid)
            Log.writeToLog(packet, "Marked not valid", "ConnectionImpl, isValid()");

        return valid;
    }
    
    //DIVERSE METODER
    private boolean isCorrectAck(KtnDatagram ack) {
        if(ack == null)
                return false;
        if(ack.getFlag() != Flag.ACK)
                return false;
        if(ack.getAck() != lastDataPacketSent.getSeq_nr())
                return false;
        return true;
    }

    private int getFirstUnusedPort(int inPort) {
            int i = inPort + 1;
            while(usedPorts.containsKey(i))
                    i++;
            usedPorts.put(i, true);
            return i;
    }
    
    private void reset() {
        myPort = originalPort;
        remoteAddress = null;
        remotePort = -1;
        nextSequenceNo = (int)(Math.random()*10000 + 1);
        lastValidPacketReceived = null;
        state = State.CLOSED;
    }

    public Object clone() {
        ConnectionImpl newCon = new ConnectionImpl(myPort);
        newCon.myAddress = myAddress;
        newCon.myPort = myPort;
        newCon.remoteAddress = remoteAddress;
        newCon.remotePort = remotePort;
        newCon.lastValidPacketReceived = lastValidPacketReceived;
        return newCon;
    }
    
    private boolean isCorrectFin(KtnDatagram receivedFin) {
        return receivedFin != null &&
                receivedFin.getFlag() == Flag.FIN &&
                receivedFin.getSeq_nr() == lastValidPacketReceived.getSeq_nr() + 1;
    }  
}
