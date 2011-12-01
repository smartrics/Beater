package smartrics.beater;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A client to the BeaterServer. This is a sample client that offers the ability
 * to simply ping a server or periodically poll.
 * 
 * @author smartrics
 * 
 */
public class BeaterClient {

	/**
	 * The listener of success/failure conditions. If registered with this
	 * client it will be notified by the client about server connection success
	 * or failure.
	 * 
	 * @author fabrizio
	 * 
	 */
	public interface ProtocolListener {
		/**
		 * Called when connection to server fails.
		 * @param failure
		 */
		public void onFailure(String failure);
		
		/**
		 * Called when connection to server succeeds.
		 */
		public void onSuccess();
	}

	private int serverPort, pollFrequency, maxRetries;
	private AtomicBoolean stopFlag = new AtomicBoolean(true);
	private BeaterMessageListener listener;
	private ScheduledExecutorService scheduler;
	private ScheduledFuture<?> clientFuture;

	/**
	 * A client object polling the server on a specified port.
	 * @param serverPort the server port
	 * @param pollFrequencyMs the polling frequency
	 * @param maxRetries the max number of failed attempts before declaring failure.
	 */
	public BeaterClient(int serverPort, int pollFrequencyMs, int maxRetries, ScheduledExecutorService scheduler) {
		this.serverPort = serverPort;
		this.maxRetries = maxRetries;
		if (this.maxRetries < 1) {
			this.maxRetries = 1;
		}
		this.scheduler = scheduler;
		if(scheduler == null) {
			throw new IllegalArgumentException("Null scheduler");
		}
		this.pollFrequency = pollFrequencyMs;
	}

	public BeaterClient(int serverPort, ScheduledExecutorService scheduler) {
		this(serverPort, 1000, 1, scheduler);
	}

	public BeaterClient(int serverPort, int pollFrequencyMs, ScheduledExecutorService scheduler) {
		this(serverPort, pollFrequencyMs, 1, scheduler);
	}

	/**
	 * Sets a listener for progress messages. It can be used to plug in a logger for example.
	 * @param l the listener
	 */
	public void setMessageListener(BeaterMessageListener l) {
		this.listener = l;
	}

	/**
	 * Stops polling for server presence.
	 */
	public void stop() {
		stopFlag.set(true);
		clientFuture.cancel(true);
		try {
			clientFuture.get();
		} catch (Exception e) {
		}
	}
	
	/**
	 * Starts the server notifying the input listener of success/failure.
	 * 
	 * @param listener the recipient of notifications.
	 */
	public void start(final ProtocolListener protocolListener) {
		notifyListener("About to start client");
		Runnable task = new Runnable() {
			public void run() {
				stopFlag.set(false);
				String failMessage = "server not available";
				boolean success = true;
				while (!stopFlag.get()) {
					int retries = maxRetries;
					while (retries > 0) {
						try {
							notifyListener("About to ping server [port=" + serverPort + ", attempt=" + (maxRetries - retries + 1) + "]");
							success = ping();
							break;
						} catch (RuntimeException e) {
							notifyListener("Exception when pinging server [port=" + serverPort + ", attempt=" + (maxRetries - retries + 1) + ", message=" + e.getMessage() + "]");
							success = false;
						}
						if(!success) {
							retries--;
							notifyListener("Unsuccesful attempt. One less retry. " + retries);
						}
						if (retries == 0) {
							success=false;
							failMessage = "Server not contactable after " + maxRetries + " retries";
							notifyListener("No more retries left. [port=" + serverPort + ", attempt=" + (maxRetries - retries + 1) + ", message=" + failMessage + "]");
							break;
						} else {
							notifyListener("Retrying to connect. [port=" + serverPort + ", attempt=" + (maxRetries - retries + 1) + "]");
						}
					}
					notifyListener("Retries. " + retries + ", Success=" + success);
					notifyProtocolListener(success, failMessage, protocolListener);
				}
			}
		};
		clientFuture = scheduler.scheduleAtFixedRate(task, 0, pollFrequency, TimeUnit.MILLISECONDS);
	}

	public boolean ping() {
		SocketAddress endpoint = new InetSocketAddress(serverPort);
		Socket socket = new Socket();
		boolean success = false;
		try {
			socket.connect(endpoint, 1000);
			success = true;
		} catch(SocketTimeoutException e) {
			notifyListener("Connection to server timed out [message=" + e.getMessage() + "]");
		} catch (IOException e) {
			notifyListener("Connection to server failed [message=" + e.getMessage() + "]");
		} finally {
			try {
				socket.close();
			} catch (IOException e) {
			}
		}
		return success;
	}
	
	private void notifyProtocolListener(boolean isSuccess, String failMessage, ProtocolListener protocolListener) {
		if(protocolListener == null) {
			return;
		}
		notifyListener("notifyProtocolListener [isSuccess=" + isSuccess + "]");
		try {
			if (!isSuccess) {
				notifyListener("Failed to ping server [message=" + failMessage + "]");
				protocolListener.onFailure(failMessage);
			} else {
				notifyListener("Server pinged successfully. isSuccess=" + isSuccess);
				protocolListener.onSuccess();
			}
		} catch (RuntimeException e) {

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
}
