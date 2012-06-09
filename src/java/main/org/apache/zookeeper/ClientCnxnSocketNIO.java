/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.client.ZooKeeperSaslClient;
import org.apache.zookeeper.proto.ReplyHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.ClientCnxn.EndOfStreamException;
import org.apache.zookeeper.ClientCnxn.Packet;
import org.apache.zookeeper.ZooDefs.OpCode;

public class ClientCnxnSocketNIO extends ClientCnxnSocket {
    private static final Logger LOG = LoggerFactory
            .getLogger(ClientCnxnSocketNIO.class);

    private final Selector selector = Selector.open();

    private SelectionKey sockKey;

    private SocketAddress localSocketAddress;

    private SocketAddress remoteSocketAddress;

    ClientCnxnSocketNIO() throws IOException {
        super();
    }

    @Override
    boolean isConnected() {
        return sockKey != null;
    }
    
    /**
     * @return true if a packet was received
     * @throws InterruptedException
     * @throws IOException
     */
    void doIO(List<Packet> pendingQueue, LinkedList<Packet> outgoingQueue,
              ClientCnxn cnxn)
      throws InterruptedException, IOException {
        SocketChannel sock = (SocketChannel) sockKey.channel();
        if (sock == null) {
            throw new IOException("Socket is null!");
        }
        if (sockKey.isReadable()) {
            int rc = sock.read(incomingBuffer);
            if (rc < 0) {
                throw new EndOfStreamException(
                        "Unable to read additional data from server sessionid 0x"
                                + Long.toHexString(sessionId)
                                + ", likely server has closed socket");
            }
            if (!incomingBuffer.hasRemaining()) {
                incomingBuffer.flip();
                if (incomingBuffer == lenBuffer) {
                    recvCount++;
                    readLength();
                } else if (!initialized) {
                    readConnectResult();
                    enableRead();
                    if (!outgoingQueue.isEmpty()) {
                        enableWrite();
                    }
                    lenBuffer.clear();
                    incomingBuffer = lenBuffer;
                    updateLastHeard();
                    initialized = true;
                } else {
                    sendThread.readResponse(incomingBuffer);
                    lenBuffer.clear();
                    incomingBuffer = lenBuffer;
                    updateLastHeard();
                }
            }
        }
        if (sockKey.isWritable()) {
            LinkedList<Packet> pending = new LinkedList<Packet>();
            synchronized (outgoingQueue) {
                if (!outgoingQueue.isEmpty()) {
                    Packet p = null;
                    if (cnxn.sendThread.clientTunneledAuthenticationInProgress()) {
                        // Since client's authentication with server is in progress,
                        // send only ping packets. Find the first such packet, if any, to send.
                        Iterator<Packet> iter = outgoingQueue.listIterator();
                        while(iter.hasNext()) {
                            p = iter.next();
                            if ((p.requestHeader == null)||
                                 (p.requestHeader.getType() == OpCode.ping)||
                                 (p.requestHeader.getType() == OpCode.auth)) {
                                // We've found a ping packet: send it.
                                break;
                            } else {
                                // Non-ping packet:
                                // defer it until later, leaving it in the queue
                                // until authentication completes.
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("deferring permission-requiring packet: " + p);
                                }
                                p = null;
                            }
                        }
                    } else {
                        // Tunnelled authentication is not in progress: just
                        // send the first packet in the queue.
                        p = outgoingQueue.getFirst();
                    }
                    if (p != null) {
                        outgoingQueue.removeFirstOccurrence(p);
                        // update Xid to make sure that packet is sent in correct order.
                        if (false && p.requestHeader != null) {
                            int xid = cnxn.getXid();
                            p.requestHeader.setXid(xid);
                            if (p.replyHeader != null) {
                                p.replyHeader.setXid(xid);
                            } else {
                                p.replyHeader = new ReplyHeader();
                                p.replyHeader.setXid(xid);
                            }
                            p.rewritebb();
                        }
                        updateLastSend();
                        ByteBuffer pbb = p.bb;
                        sock.write(pbb);
                        if (!pbb.hasRemaining()) {
                            sentCount++;
                            if (p.requestHeader != null
                              && p.requestHeader.getType() != OpCode.ping
                              && p.requestHeader.getType() != OpCode.auth) {
                                pending.add(p);
                            }
                        }
                    }
                    synchronized(pendingQueue) {
                        pendingQueue.addAll(pending);
                    }
                }
            }
        }
    }

    @Override
    void cleanup() {
        if (sockKey != null) {
            SocketChannel sock = (SocketChannel) sockKey.channel();
            sockKey.cancel();
            try {
                sock.socket().shutdownInput();
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Ignoring exception during shutdown input", e);
                }
            }
            try {
                sock.socket().shutdownOutput();
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Ignoring exception during shutdown output",
                            e);
                }
            }
            try {
                sock.socket().close();
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Ignoring exception during socket close", e);
                }
            }
            try {
                sock.close();
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Ignoring exception during channel close", e);
                }
            }
        }
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("SendThread interrupted during sleep, ignoring");
            }
        }
        sockKey = null;
    }
 
    @Override
    void close() {
        try {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Doing client selector close");
            }
            selector.close();
            if (LOG.isTraceEnabled()) {
                LOG.trace("Closed client selector");
            }
        } catch (IOException e) {
            LOG.warn("Ignoring exception during selector close", e);
        }
    }
    
    /**
     * create a socket channel.
     * @return the created socket channel
     * @throws IOException
     */
    SocketChannel createSock() throws IOException {
        SocketChannel sock;
        sock = SocketChannel.open();
        sock.configureBlocking(false);
        sock.socket().setSoLinger(false, -1);
        sock.socket().setTcpNoDelay(true);
        return sock;
    }

    /**
     * register with the selection and connect
     * @param sock the {@link SocketChannel} 
     * @param addr the address of remote host
     * @throws IOException
     */
    void registerAndConnect(SocketChannel sock, InetSocketAddress addr) 
    throws IOException {
        sockKey = sock.register(selector, SelectionKey.OP_CONNECT);
        boolean immediateConnect = sock.connect(addr);
        if (immediateConnect) {
            sendThread.primeConnection();
        }
    }
    
    @Override
    void connect(InetSocketAddress addr) throws IOException {
        SocketChannel sock = createSock();
        try {
           registerAndConnect(sock, addr);
      } catch (IOException e) {
            LOG.error("Unable to open socket to " + addr);
            sock.close();
            throw e;
        }
        initialized = false;

        /*
         * Reset incomingBuffer
         */
        lenBuffer.clear();
        incomingBuffer = lenBuffer;
    }

    /**
     * Returns the address to which the socket is connected.
     * 
     * @return ip address of the remote side of the connection or null if not
     *         connected
     */
    @Override
    SocketAddress getRemoteSocketAddress() {
        return remoteSocketAddress;
    }

    /**
     * Returns the local address to which the socket is bound.
     * 
     * @return ip address of the remote side of the connection or null if not
     *         connected
     */
    @Override
    SocketAddress getLocalSocketAddress() {
        return localSocketAddress;
    }
    
    private void updateSocketAddresses() {
        Socket socket = ((SocketChannel) sockKey.channel()).socket();
        localSocketAddress = socket.getLocalSocketAddress();
        remoteSocketAddress = socket.getRemoteSocketAddress();
    }

    @Override
    synchronized void wakeupCnxn() {
        selector.wakeup();
    }
    
    @Override
    void doTransport(int waitTimeOut, List<Packet> pendingQueue,
                     LinkedList<Packet> outgoingQueue, ClientCnxn cnxn)
            throws IOException, InterruptedException {
        selector.select(waitTimeOut);
        Set<SelectionKey> selected;
        synchronized (this) {
            selected = selector.selectedKeys();
        }
        // Everything below and until we get back to the select is
        // non blocking, so time is effectively a constant. That is
        // Why we just have to do this once, here
        updateNow();
        for (SelectionKey k : selected) {
            SocketChannel sc = ((SocketChannel) k.channel());
            if ((k.readyOps() & SelectionKey.OP_CONNECT) != 0) {
                if (sc.finishConnect()) {
                    updateLastSendAndHeard();
                    updateSocketAddresses();
                    sendThread.primeConnection();
                }
            } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                doIO(pendingQueue, outgoingQueue, cnxn);
            }
        }
        if (sendThread.getZkState().isConnected()) {
            synchronized(outgoingQueue) {
                if ((!outgoingQueue.isEmpty()) || (cnxn.sendThread.clientTunneledAuthenticationInProgress())) {
                    enableWrite();
                } else {
                    disableWrite();
                }
            }
        }
        selected.clear();
    }

    //TODO should this be synchronized?
    @Override
    void testableCloseSocket() throws IOException {
        LOG.info("testableCloseSocket() called");
        ((SocketChannel) sockKey.channel()).socket().close();
    }

    @Override
    synchronized void enableWrite() {
        int i = sockKey.interestOps();
        if ((i & SelectionKey.OP_WRITE) == 0) {
            sockKey.interestOps(i | SelectionKey.OP_WRITE);
        }
    }

    private synchronized void disableWrite() {
        int i = sockKey.interestOps();
        if ((i & SelectionKey.OP_WRITE) != 0) {
            sockKey.interestOps(i & (~SelectionKey.OP_WRITE));
        }
    }

    synchronized private void enableRead() {
        int i = sockKey.interestOps();
        if ((i & SelectionKey.OP_READ) == 0) {
            sockKey.interestOps(i | SelectionKey.OP_READ);
        }
    }

    @Override
    synchronized void enableReadWriteOnly() {
        sockKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
    }

    Selector getSelector() {
        return selector;
    }

    @Override
    void sendPacket(Packet p) throws IOException {
        SocketChannel sock = (SocketChannel) sockKey.channel();
        if (sock == null) {
            throw new IOException("Socket is null!");
        }
        ByteBuffer pbb = p.bb;
        sock.write(pbb);
    }


}
