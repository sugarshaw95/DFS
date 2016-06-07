import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.barchart.udt.ExceptionUDT;
import com.barchart.udt.SocketUDT;
import com.barchart.udt.TypeUDT;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 
 */

/**
 * @author wzy
 *
 */
class LinkMaintainer implements Runnable {

	private Node node;

	private Map<String, Timer> link_timers;

	/**
	 * @param node
	 */
	public LinkMaintainer(Node node) {
		this.node = node;
		link_timers = new ConcurrentHashMap<>();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		establish_links();
		while (true) {
			// check link timers
			link_timers.forEach((nodeID, timer) -> {
				if (!node.nodeIDs.contains(nodeID)) {// the peer node has
														// dropped
					link_timers.remove(nodeID);
				} else if (timer.isExpired()) {
					try {
						if (establish_link_m(nodeID)) {
							link_timers.remove(nodeID);
						} else {
							timer.postpone(20000 * timer.getCnt());
							timer.start();
						}
					} catch (NodeException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (ExceptionUDT e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (PackException ex) {
                                        Logger.getLogger(LinkMaintainer.class.getName()).log(Level.SEVERE, null, ex);
                                    }
				}
			});
			// check if new node inserted
			while (!node.node_inserted_lm.isEmpty())
				new_link_timer(node.node_inserted_lm.poll());
			// check if node deleted
			while (!node.node_deleted_lm.isEmpty()) {
				String nodeID = node.node_deleted_lm.poll();
				if (node.links_p.containsKey(nodeID)) {
					node.links_p_t.get(nodeID).interrupt();
					node.links_p_t.remove(nodeID);
					node.links_p_l.remove(nodeID);
					node.links_p.remove(nodeID);
				} else
					link_timers.remove(nodeID);
			}
			// find out link broken
			node.links_p.forEach((nodeID, socket) -> {
				if (socket.isClosed()) {
					String str = new String();
					Map<String, String> pac;
					node.links_p.remove(nodeID);
					node.links_p_t.remove(nodeID);
					node.links_p_l.remove(nodeID);
					pac = new ConcurrentHashMap<String, String>();
					pac.put("ID", nodeID);
					pac.put("Connectivity", "false");
					try {
						str = Packer.pack("LinkC", pac);
					} catch (NodeException e) {// just used for debug
						e.printStackTrace();
					}
					SocketUDT server = null;
					try {
						server = new SocketUDT(TypeUDT.STREAM);
						server.setBlocking(true);
						node.server_link_lock.lock();
						server.bind(new InetSocketAddress(node.IP_local_server, node.Port_local_server));
						server.connect(new InetSocketAddress(node.server_host, node.server_port));
						try {
							server.send(str.getBytes(Charset.forName("ISO-8859-1")));// send
																						// packetLinkC:false
						} catch (ExceptionUDT e1) {
							// TODO Auto-generated catch block
							e1.printStackTrace();
						} finally {
							try {
								server.close();
							} catch (ExceptionUDT e1) {
								// TODO Auto-generated catch block
								e1.printStackTrace();
							}
							node.server_link_lock.unlock();
						}
					} catch (ExceptionUDT e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					new_link_timer(nodeID);
				}
			});
			// check if other Nodes try to establish links with this
			while (!node.messages_from_server.get("Link").isEmpty()) {
				Map<String, String> pac = node.messages_from_server.get("Link").poll();
				if (pac.get("type").equals("LinkE") && pac.get("type_d").equals("03")) {
					try {
						establish_link_s(pac.get("ID"), pac.get("IP"), Integer.parseInt(pac.get("Port")));
					} catch (ExceptionUDT e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				} else {
					// TODO Something wrong
				}
			}
		}

	}

	/**
	 * Try to establish links with every Node in the NodeID table.
	 */
	private void establish_links() {
		node.nodeIDs.forEach(nodeID -> {
			try {
				if (!establish_link_m(nodeID)) {
					new_link_timer(nodeID);
				}
			} catch (NodeException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (ExceptionUDT e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (PackException ex) {
                        Logger.getLogger(LinkMaintainer.class.getName()).log(Level.SEVERE, null, ex);
                    }
		});
		return;
	}

	/**
	 * @param ID_p
	 * @return
	 * @throws ExceptionUDT
	 * @throws NodeException
	 */
	private boolean establish_link_m(String ID_p) throws NodeException, ExceptionUDT, PackException {
		if (node.ID.equals(ID_p) || !node.nodeIDs.contains(ID_p) || node.links_p.containsKey(ID_p))
			return false;// TODO Something wrong
		byte arr[] = new byte[1024];
		String str = new String();
		Map<String, String> pac;
		String IP_p = new String();
		int Port_p = 0;
		SocketUDT server = new SocketUDT(TypeUDT.STREAM);
		server.setBlocking(true);
		SocketUDT sock = new SocketUDT(TypeUDT.STREAM);
		sock.setBlocking(true);
		node.server_link_lock.lock();
		try {
			server.bind(new InetSocketAddress(node.IP_local_server, node.Port_local_server));
			server.connect(new InetSocketAddress(node.server_host, node.server_port));
			pac = new ConcurrentHashMap<String, String>();
			pac.put("ID", ID_p);
			try {
				str = Packer.pack("LinkE", "01", pac);
			} catch (NodeException e1) {// just used for debug
				e1.printStackTrace();
			}
			server.send(str.getBytes(Charset.forName("ISO-8859-1")));// send
																		// packet
																		// LinkE01
			server.receive(arr);// receive packet LinkE03
			str = new String(arr, Charset.forName("ISO-8859-1")).trim();
            node.empty_arr(str.length(), arr);
			pac = Packer.unpack(str);
			if (pac.get("type").equals("ERR")) {// another Node is trying to
												// connect with ID_p
				try {
					server.close();
				} catch (ExceptionUDT e) {
					e.printStackTrace();
				}
				node.server_link_lock.unlock();
				return false;
			}
			IP_p = pac.get("IP");
			Port_p = Integer.parseInt(pac.get("Port"));
			sock.bind(new InetSocketAddress(node.IP_local_server, node.Port_local_server));
                        sock.setRendezvous(true);
                        try {
				sock.connect(new InetSocketAddress(IP_p, Port_p));
                        } catch (ExceptionUDT e) {
                                pac = new ConcurrentHashMap<>();
				pac.put("ID", ID_p);
				pac.put("Connectivity", "false");
				try {
					str = Packer.pack("LinkC", pac);
				} catch (NodeException e1) {// just used for debug
				}
				server = new SocketUDT(TypeUDT.STREAM);
                                server.bind(new InetSocketAddress(node.IP_local_server, node.Port_local_server));
                                server.connect(new InetSocketAddress(node.server_host, node.server_port));
			
                                server.send(str.getBytes(Charset.forName("ISO-8859-1")));// send
																			// packet
																			// LinkC:false
				try {
					server.close();
				} catch (ExceptionUDT e1) {
				}
				node.server_link_lock.unlock();
				return false;
			}
			pac = new ConcurrentHashMap<>();
			pac.put("ID", node.ID);
			try {
				str = Packer.pack("LinkE", "04", pac);
			} catch (NodeException e) {// just used for debug
			}
			sock.send(str.getBytes(Charset.forName("ISO-8859-1")));// send
																	// packet
																	// LinkE04
			sock.receive(arr);// receive packet LinkE04
			// TODO
			pac = new ConcurrentHashMap<>();
			pac.put("ID", ID_p);
			pac.put("Connectivity", "true");
			try {
				str = Packer.pack("LinkC", pac);
			} catch (NodeException e) {// just used for debug
			}
                        server = new SocketUDT(TypeUDT.STREAM);
			server.bind(new InetSocketAddress(node.IP_local_server, node.Port_local_server));
			server.connect(new InetSocketAddress(node.server_host, node.server_port));
			server.send(str.getBytes(Charset.forName("ISO-8859-1")));// send
																		// packet
																		// LinkC:true
		} catch (ExceptionUDT e) {
			sock.close();
			throw e;
		} finally {
			try {
				server.close();
			} catch (ExceptionUDT e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			node.server_link_lock.unlock();
		}
		node.links_p.put(ID_p, sock);
		node.links_p_t.put(ID_p, new Thread(new NodeLink(ID_p, sock, node)));
		node.links_p_t.get(ID_p).start();
		node.links_p_l.put(ID_p, new ReentrantLock());
		return true;
	}

	/**
	 * @param ID_p
	 * @param IP_p
	 * @param Port_p
	 * @return
	 * @throws ExceptionUDT
	 */
	private boolean establish_link_s(String ID_p, String IP_p, int Port_p) throws ExceptionUDT {
		if (node.ID.equals(ID_p) || (IP_p == null) || (IP_p.length() == 0))
			return false;// TODO Something wrong
		if (!node.nodeIDs.contains(ID_p)) {
			node.nodeIDs.add(ID_p);
		}
		if (node.links_p.containsKey(ID_p)) {
			node.links_p_t.get(ID_p).interrupt();
			node.links_p_t.remove(ID_p);
			node.links_p_l.remove(ID_p);
			node.links_p.remove(ID_p);
		}
		byte arr[] = new byte[1024];
		String str = new String();
		Map<String, String> pac;
		SocketUDT server = new SocketUDT(TypeUDT.STREAM);
		server.setBlocking(true);
		SocketUDT sock = new SocketUDT(TypeUDT.STREAM);
		sock.setBlocking(true);
		node.server_link_lock.lock();
		try {
			server.bind(new InetSocketAddress(node.IP_local_server, node.Port_local_server));
			server.connect(new InetSocketAddress(node.server_host, node.server_port));
			sock.bind(new InetSocketAddress(node.IP_local_server, node.Port_local_server));
			sock.setRendezvous(true);
			try {
				sock.connect(new InetSocketAddress(IP_p, Port_p));
			} catch (ExceptionUDT e) {
				pac = new ConcurrentHashMap<String, String>();
				pac.put("ID", ID_p);
				pac.put("Connectivity", "false");
				try {
					str = Packer.pack("LinkC", pac);
				} catch (NodeException e1) {// just used for debug
					e1.printStackTrace();
				}
				server.send(str.getBytes(Charset.forName("ISO-8859-1")));// send
																			// packet
																			// LinkC:false
				try {
					server.close();
				} catch (ExceptionUDT e1) {
					e1.printStackTrace();
				}
				node.server_link_lock.unlock();
				return false;
			}
			sock.receive(arr);// receive packet LinkE04
			// TODO
			pac = new ConcurrentHashMap<String, String>();
			pac.put("ID", node.ID);
			try {
				str = Packer.pack("LinkE", "04", pac);
			} catch (NodeException e) {// just used for debug
				e.printStackTrace();
			}
			sock.send(str.getBytes(Charset.forName("ISO-8859-1")));// send
																	// packet
																	// LinkE04

			pac = new ConcurrentHashMap<String, String>();
			pac.put("ID", ID_p);
			pac.put("Connectivity", "true");
			try {
				str = Packer.pack("LinkC", pac);
			} catch (NodeException e) {// just used for debug
				e.printStackTrace();
			}
			server.send(str.getBytes(Charset.forName("ISO-8859-1")));// send
																		// packet
																		// LinkC:true
		} catch (ExceptionUDT e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			try {
				server.close();
			} catch (ExceptionUDT e) {
				e.printStackTrace();
			}
			node.server_link_lock.unlock();
		}
		node.links_p.put(ID_p, sock);
		node.links_p_t.put(ID_p, new Thread(new NodeLink(ID_p, sock, node)));
		node.links_p_t.get(ID_p).start();
		node.links_p_l.put(ID_p, new ReentrantLock());
		return true;

	}

	/**
	 * @param nodeID
	 */
	private void new_link_timer(String nodeID) {
		if (nodeID == null)
			return;
		if (!node.nodeIDs.contains(nodeID))
			return;
		Timer timer = new Timer(10000, 3600000);
		timer.start();
		link_timers.put(nodeID, timer);
		return;
	}

}