package smartrics.beater;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A simple server class listening functioning as a presence indicator.
 * 
 * @author smartrics
 * 
 */
public class BeaterServer {
	private int port;
	final private AtomicBoolean stopFlag, serverStarted;
	private Future<Boolean> serverActivity;
	private int soTimeout;
	private BeaterMessageListener listener;
	private AtomicLong pingCounter;
	private ScheduledExecutorService scheduler;
	private ServerSocket serverSocket;
	
	/**
	 * Builds a server to listen on a server selected port. The server opens a
	 * socket on the selected port and polls for connections every 100ms.
	 * 
	 * @param scheduler explicit threading policy
	 * @throws IllegalArgumentException
	 *             if port is not in a valid range or if soTimeout is negative or if scheduler is null.
	 */
	public BeaterServer(ScheduledExecutorService scheduler) {
		this(0, scheduler);
	}

	/**
	 * Builds a server to listen on a client selected port. The server opens a
	 * socket on the selected port and polls for connections every 100ms.
	 * 
	 * @param port
	 *            the port on this host the server listens for connections to.
	 * @param scheduler explicit threading policy
	 * @throws IllegalArgumentException
	 *             if port is not in a valid range or if soTimeout is negative or if scheduler is null.
	 */
	public BeaterServer(int port, ScheduledExecutorService scheduler) {
		this(port, 100, scheduler);
	}

	/**
	 * Builds a server to listen on a client selected port, polling for
	 * connections on a period specified by the client.
	 * 
	 * @param port
	 *            the port on this host the server listens for connections to.
	 * @param soTimeout
	 *            the period in ms to poll for connections.
	 * @param scheduler explicit threading policy
	 * @throws IllegalArgumentException
	 *             if port is not in a valid range or if soTimeout is negative or if scheduler is null.
	 */
	public BeaterServer(int port, int soTimeout, ScheduledExecutorService scheduler) {
		if (port < 0 || port > 65535) {
			throw new IllegalArgumentException("Invalid port [port=" + port + "]");
		}
		if (soTimeout < 0) {
			throw new IllegalArgumentException("Invalid soTimeout [soTimeout=" + soTimeout + "]");
		}
		if(scheduler == null){
			throw new IllegalArgumentException("Null scheduler");
		}
		this.port = port;
		this.soTimeout = soTimeout;
		this.stopFlag = new AtomicBoolean(true);
		this.serverStarted = new AtomicBoolean(false);
		this.pingCounter = new AtomicLong(0);
		this.scheduler = scheduler;
	}

	/**
	 * Sets a listener for progress messages. It can be used to plug in a logger for example.
	 * @param l the listener
	 */
	public void setMessageListener(BeaterMessageListener l) {
		this.listener = l;
	}
	
	/**
	 * @return The port the server listens to.
	 */
	public int getPort() {
		return this.port;
	}

	/**
	 * 
	 * @return true if the server is actively polling for connections.
	 */
	public boolean isStarted() {
		return serverStarted.get();
	}

	public long getPingCount() {
		return this.pingCounter.get();
	}
	
	/**
	 * The server starts listening. It spawns a thread that periodically accepts
	 * connections on the selected port. As soon as a connection is accepted, it
	 * is closed straight away. A client will know if this server is not
	 * responding if connection to the socket times out. The server will continue accept connections until stopped (@see BeaterServer#stop).
	 * 
	 * @throws IllegalStateException
	 *             if the server is already started or if the server cannot open/listen to the selected port.
	 */
	public void start() {
		if (serverStarted.get()) {
			throw new IllegalStateException("Server already started. " + toString());
		}
		Callable<Boolean> task;
		try {
			serverSocket = new ServerSocket(port);
			serverSocket.setSoTimeout(soTimeout);
			port = serverSocket.getLocalPort();
			notifyListener("Server started. " + toString());
			serverStarted.set(true);
			task = new Callable<Boolean>() {
	
				public Boolean call() {
					notifyListener("Starting server thread. " + toString());
					stopFlag.set(false);
					while (!stopFlag.get()) {
						Socket connection = null;
						try {
							connection = serverSocket.accept();
							notifyListener("Connection accepted on port. " + toString());
							connection.close();
							notifyListener("Connection closed on port. " + toString());
							pingCounter.incrementAndGet();
						} catch (SocketTimeoutException e) {
							notifyListener("Server accept timed out. Retrying. " + toString());
						} catch (IOException e) {
							notifyListener("Server accept caused exception [message=" + e.getMessage() + "]. Retrying. " + toString());
						}
					}
					notifyListener("Server socket closed. " + toString());
					return true;
				}
			};
		} catch (IOException e) {
			stopFlag.set(true);
			throw new IllegalStateException("Unable to open socket on port. " + toString(), e);
		}
		serverActivity = scheduler.submit(task);
		notifyListener("Waiting for server to fully complete start. " + toString());
		for (;;) {
			try {
				Thread.sleep(100);
				if (isStarted()) {
					notifyListener("Server started. " + toString());
					break;
				}
			} catch (InterruptedException e) {

			}
		}
	}

	/**
	 * Stops the server. The call blocks until the server is fully stopped (e.g. the current thread joins with the server thread).
	 */
	public void stop() {
		stopFlag.set(true);
		if (serverStarted.get()) {
			notifyListener("About to stop server. " + toString());
			try {
				serverActivity.get();
				serverSocket.close();
			} catch (InterruptedException e) {
				notifyListener("Interrupted whilst waiting for the server start to finish. [message=" + e.getMessage() + "]" + toString());
			} catch (ExecutionException e) {
				notifyListener("Server start caused exception. [message=" + e.getMessage() + "]" + toString());
			} catch (IOException e) {
				notifyListener("Unable to close cleanly the server socket. [message=" + e.getMessage() + "]" + toString());
			}
			serverStarted.set(false);
		} else {
			notifyListener("Server already stopped. " + toString());
		}
	}

	private void notifyListener(String message) {
		if(listener == null) {
			return;
		}
		try {
			listener.onMessage(message);
		} catch(RuntimeException e) {
			
		}
	}
	
	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append("Server@").append(hashCode());
		sb.append("[").append("port=").append(port).append(", stopFlag=").append(stopFlag.get());
		sb.append(", pingCounter=").append(this.pingCounter).append("]");
		return sb.toString();
	}
}
