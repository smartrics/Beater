package smartrics.beater;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import smartrics.beater.BeaterClient.ProtocolListener;

public class BeaterTest {

	private final class CounterProtocolListener implements ProtocolListener, BeaterMessageListener {

		private boolean stopOnFailure;
		private BeaterClient client;

		public CounterProtocolListener(boolean stopOnFailure, BeaterClient client) {
			this.stopOnFailure = stopOnFailure;
			this.client = client;
		}
		
		public AtomicInteger failures = new AtomicInteger(0);
		public AtomicInteger successes  = new AtomicInteger(0);
		public List<String> messages = new ArrayList<String>();
		
		@Override
		public synchronized void onMessage(String m) {
			messages.add(m);
		}
		
		@Override
		public void onFailure(String failure) {
			failures.incrementAndGet();
			if(stopOnFailure) {
				client.stop();
			}
		}

		@Override
		public void onSuccess() {
			successes.incrementAndGet();
		}
		
	}
	
	private final class ServerControllerProtocolListener implements ProtocolListener {
		private final BeaterServer server;
		private final CountDownLatch sync;
		int successes = 0, pings;
		String messageFailure = null;

		private ServerControllerProtocolListener(int pingCount, BeaterServer server, CountDownLatch sync) {
			this.server = server;
			this.pings = pingCount;
			this.sync = sync;
		}

		@Override
		public void onSuccess() {
			successes++;
			sysOutLogger().onMessage("SUCCESS #" + successes);
			if (successes == pings) {
					sysOutLogger().onMessage("stopping");
					server.stop();
			}
		}

		@Override
		public void onFailure(String failure) {
			sysOutLogger().onMessage("FAIL #" + failure);
			this.messageFailure = failure;
			sync.countDown();
		}
	}

	private BeaterServer server;
	private ScheduledExecutorService clientScheduler;
	private ScheduledExecutorService serverScheduler;

	@After
	public void stop() {
		server.stop();
	}
	
	@Before
	public void start() {
		clientScheduler = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 100);
		serverScheduler = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 100);
		server = new BeaterServer(serverScheduler);
		server.setMessageListener(null);
		server.start();
	}
	
	@Test
	public void basicClientServerComms() {
		BeaterClient client = new BeaterClient(server.getPort(), clientScheduler);
		boolean result = client.ping();
		assertThat(result, is(equalTo(true)));
	}

	@Test(timeout = 5000)
	public void clientLoopsForeverUntilServerStopped() throws Exception {
		BeaterClient client = new BeaterClient(server.getPort(), 100, clientScheduler);
		client.setMessageListener(sysOutLogger());
		final CountDownLatch sync = new CountDownLatch(1);
		ServerControllerProtocolListener listener = new ServerControllerProtocolListener(5, server, sync);
		client.start(listener);
		sync.await();
		assertThat(listener.messageFailure, is(equalTo("server not available")));

	}
	
	@Test(timeout = 20000)
	public void lotsOfClientsLoopUntilServerStops() throws Exception {
		final int maxClients = 100;
		List<BeaterClient> clients = new ArrayList<BeaterClient>(maxClients);
		List<CounterProtocolListener> listeners = new ArrayList<CounterProtocolListener>(maxClients);
		for(int c = 0; c < maxClients; c++) {
			BeaterClient client = new BeaterClient(server.getPort(), 1000, 10, clientScheduler);
			boolean stopOnFailure = true;
			CounterProtocolListener listener = new CounterProtocolListener(stopOnFailure, client);
			clients.add(client);
			client.setMessageListener(listener);
			listeners.add(listener);
			Thread.sleep(10);
			client.start(listener);
		}

		long totSuccesses = 0;
		long totFailures = 0;
		for(int c = 0; c < maxClients; c++) {
			CounterProtocolListener l = listeners.get(c);
			totSuccesses += l.successes.get();
			totFailures += l.failures.get();
		}
		assertThat(totSuccesses > 0, is(true));
	}
	
	@Test(timeout = 10000)
	public void multipleClientsLoopForeverUntilServerStopped() throws Exception {
		int clients = 50;
		final CountDownLatch sync = new CountDownLatch(clients);
		for(int i = 0; i<clients; i++) {
			BeaterClient client = new BeaterClient(server.getPort(), 100, clientScheduler);
			client.start(new ServerControllerProtocolListener(10, server, sync));
		}
		sync.await();
	}

	@Test(timeout = 5000) 
	public void clientReceivesOnFailureEventIfServerCantBeContactedAfterMaxRetriesAttempts() throws Exception {
		server.setMessageListener(sysOutLogger());
		int failures = 3;
		BeaterClient client = new BeaterClient(server.getPort(), 100, failures, clientScheduler);
		client.setMessageListener(sysOutLogger());
		server.stop();
		final CountDownLatch sync = new CountDownLatch(failures);
		client.start(new ProtocolListener() {

			@Override
			public void onFailure(String failure) {
				sysOutLogger().onMessage("failure: '" + failure + "'");
				sync.countDown();
			}

			@Override
			public void onSuccess() {
				sysOutLogger().onMessage("Ouch!");
				fail("Should have never been called");
			}
			
		});
		sync.await();
	}
	

	public BeaterMessageListener sysOutLogger() {
		return new BeaterMessageListener() {
			SimpleDateFormat f = new SimpleDateFormat("hh:MM:ss.SSS");
			@Override
			public void onMessage(String message) {
				System.out.println(f.format(new Date()) + "  " + message);
			}
			
		};
	}
}
