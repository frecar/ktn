/*
 * Created on Oct 27, 2004
 */
package no.ntnu.fp.net.co;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
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
 * @author Sebj�rn Birkeland and Stein Jakob Nordb�
 * @see no.ntnu.fp.net.co.Connection
 * @see no.ntnu.fp.net.cl.ClSocket
 */
public class ConnectionImpl extends AbstractConnection {

    /** Keeps track of the used ports for each server port. */
    private static Map<Integer, Boolean> usedPorts = Collections.synchronizedMap(new HashMap<Integer, Boolean>());

    /**
     * Initialise initial sequence number and setup state machine.
     * 
     * @param myPort
     *            - the local port to associate with this connection
     */
    public ConnectionImpl(int myPort) {
    	super();
    	this.myPort = 5000;
    }

    private String getIPv4Address() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        }
        catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }
    
    
    public KtnDatagram sendUntilResponse(KtnDatagram packet) throws IOException {
    	KtnDatagram datagram = null;
    	int attempts = 0;
    	while(!isValid(datagram)) {
    	
    		attempts+=1;
    		if(attempts>10) {
        		throw new IOException("Kunne ikke sende pakken, har prøvd "+ attempts + "ganger");
    		}
    		
    		try {
    			simplySendPacket(packet);
    			datagram = receiveAck();
    		}
    		catch(Exception e) {
    			System.out.println("error");
    			continue;
    		}
		}
    	return datagram;
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

    	if(state != State.CLOSED){
    		throw new ConnectException("Socket not closed");
    	}
    	
    	this.remotePort = remotePort;
    	this.remoteAddress = "localhost";
    	
    	state = State.SYN_SENT;
    	KtnDatagram datagram = sendUntilResponse(constructInternalPacket(Flag.SYN));
    	lastValidPacketReceived = datagram;
    	sendAck(datagram, false);
    	state = State.ESTABLISHED;
    }

    /**
     * Listen for, and accept, incoming connections.
     * 
     * @return A new ConnectionImpl-object representing the new connection.
     * @see Connection#accept()
     */
    public Connection accept() throws IOException, SocketTimeoutException {
    	
    	if(state != State.CLOSED && state != State.LISTEN) {
    		throw new ConnectException("Socket not closed");
    	}
    
    	state = State.LISTEN;
    	
    	KtnDatagram datagramReceived = null;
    	
    	while(!isValid(datagramReceived)) {
    		datagramReceived = receivePacket(true);
    	}
    	
    	ConnectionImpl connection = new ConnectionImpl(5000);
    	
    	//Oppretter ny connection mot klient
    	lastValidPacketReceived = datagramReceived;
    	state = State.SYN_RCVD;
    	System.out.println("Opprettet ny socket, port. " + datagramReceived.getSrc_port());
    	sendAck(datagramReceived, true);
    	if(!isValid(receiveAck())) {
    		state = State.CLOSED;
    		throw new IOException("Feil ved oppkobling");
    	}
    	state = State.ESTABLISHED;    	
    	return connection;
    	
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
        throw new NotImplementedException();
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
        throw new NotImplementedException();
    }

    /**
     * Close the connection.
     * 
     * @see Connection#close()
     */
    public void close() throws IOException {
        throw new NotImplementedException();
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
    	if (packet != null && packet.calculateChecksum() == packet.getChecksum() && isStateValid(packet)) {
    		return true;
    	}
    	return false;
    }
    
    private boolean isStateValid(KtnDatagram packet) {
    	//er det en ack pakke sjekkes det at pakken acker forrige pakke sent, ellers er det uansett false
    	if ((packet.getFlag() == Flag.ACK || packet.getFlag() == Flag.SYN_ACK) && packet.getAck() != lastDataPacketSent.getSeq_nr()) {
    		return false;
    	}
    	// Hvis det er en fin pakke så må data være null
    	if (packet.getFlag() == Flag.FIN && packet.getPayload() != null) {
    		return false; //hadde fikset problemet med at fin dukker opp i datapakker av og til hvis abstractconnection hadde kallt isValid som i dokumentasjonen
    	}
    	// Hvis state er SYN_SENT, betyr det at pakken bør være SYN_ACK og at den er fra riktig host
    	if (state == State.SYN_SENT) {
    		remotePort = packet.getSrc_port(); //verdien blir uansett satt riktig neste gang connect kjøres selv om pakken ikke var synack, sjekker acknr
    		return (packet.getFlag() == Flag.SYN_ACK && remoteAddress.equals(packet.getSrc_addr()));
    	}
    	else if (state == State.LISTEN) {
    		return (packet.getFlag() == Flag.SYN);
    	}
    	//alle andre pakker skal port og source være stilt inn for programmet så her sjekker den etter feil
    	else if (packet.getSrc_addr() != remoteAddress && packet.getSrc_port() != remotePort) {
    		return false;
    	}
    	//dette må være ack
    	else if (state == State.SYN_RCVD) {
    		return (packet.getFlag() == Flag.ACK);
    	}
    	//ønsker her ack tilbake eller fin
    	else if (state == State.FIN_WAIT_1 || state == State.FIN_WAIT_2) {
    		return (packet.getFlag() == Flag.FIN || packet.getFlag() == Flag.ACK);
    	}
    	//dette må være fin-pakke
    	else if (state == State.CLOSE_WAIT) {
    		return (packet.getFlag() == Flag.FIN);
    	}
    	return true;
    	//sjekker til sammen etter null-pakker, checksum, remoteaddress, remoteport, seqno, 
    }
}
