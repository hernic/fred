/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;

import com.db4o.ObjectContainer;

import freenet.client.FECQueue;
import freenet.client.FetchException;
import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.node.BaseSendableGet;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.LowLevelPutException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.support.Logger;
import freenet.support.PrioritizedSerialExecutor;
import freenet.support.api.StringCallback;
import freenet.support.io.NativeThread;

/**
 * Every X seconds, the RequestSender calls the ClientRequestScheduler to
 * ask for a request to start. A request is then started, in its own 
 * thread. It is removed at that point.
 */
public class ClientRequestScheduler implements RequestScheduler {
	
	private final ClientRequestSchedulerCore schedCore;
	private final ClientRequestSchedulerNonPersistent schedTransient;
	
	private static boolean logMINOR;
	
	public static class PrioritySchedulerCallback implements StringCallback, EnumerableOptionCallback {
		final ClientRequestScheduler cs;
		private final String[] possibleValues = new String[]{ ClientRequestScheduler.PRIORITY_HARD, ClientRequestScheduler.PRIORITY_SOFT };
		
		PrioritySchedulerCallback(ClientRequestScheduler cs){
			this.cs = cs;
		}
		
		public String get(){
			if(cs != null)
				return cs.getChoosenPriorityScheduler();
			else
				return ClientRequestScheduler.PRIORITY_HARD;
		}
		
		public void set(String val) throws InvalidConfigValueException{
			String value;
			if(val == null || val.equalsIgnoreCase(get())) return;
			if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_HARD)){
				value = ClientRequestScheduler.PRIORITY_HARD;
			}else if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_SOFT)){
				value = ClientRequestScheduler.PRIORITY_SOFT;
			}else{
				throw new InvalidConfigValueException("Invalid priority scheme");
			}
			cs.setPriorityScheduler(value);
		}
		
		public String[] getPossibleValues() {
			return possibleValues;
		}
		
		public void setPossibleValues(String[] val) {
			throw new NullPointerException("Should not happen!");
		}
	}
	
	/** Long-lived container for use by the selector thread.
	 * We commit when we move a request to a lower retry level.
	 * We need to refresh objects when we activate them.
	 */
	final ObjectContainer selectorContainer;
	
	/** This DOES NOT PERSIST */
	private final OfferedKeysList[] offeredKeys;
	// we have one for inserts and one for requests
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	final RandomSource random;
	private final RequestStarter starter;
	private final Node node;
	public final String name;
	private final CooldownQueue transientCooldownQueue;
	private final CooldownQueue persistentCooldownQueue;
	final PrioritizedSerialExecutor databaseExecutor;
	final DatastoreChecker datastoreChecker;
	public final ClientContext clientContext;
	final DBJobRunner jobRunner;
	
	public static final String PRIORITY_NONE = "NONE";
	public static final String PRIORITY_SOFT = "SOFT";
	public static final String PRIORITY_HARD = "HARD";
	private String choosenPriorityScheduler; 
	
	public ClientRequestScheduler(boolean forInserts, boolean forSSKs, RandomSource random, RequestStarter starter, Node node, NodeClientCore core, SubConfig sc, String name, ClientContext context) {
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		this.selectorContainer = node.db;
		schedCore = ClientRequestSchedulerCore.create(node, forInserts, forSSKs, selectorContainer, COOLDOWN_PERIOD, core.clientDatabaseExecutor, this, context);
		schedTransient = new ClientRequestSchedulerNonPersistent(this, forInserts, forSSKs);
		persistentCooldownQueue = schedCore.persistentCooldownQueue;
		this.databaseExecutor = core.clientDatabaseExecutor;
		this.datastoreChecker = core.storeChecker;
		this.starter = starter;
		this.random = random;
		this.node = node;
		this.clientContext = context;
		
		this.name = name;
		sc.register(name+"_priority_policy", PRIORITY_HARD, name.hashCode(), true, false,
				"RequestStarterGroup.scheduler"+(forSSKs?"SSK" : "CHK")+(forInserts?"Inserts":"Requests"),
				"RequestStarterGroup.schedulerLong",
				new PrioritySchedulerCallback(this));
		
		this.choosenPriorityScheduler = sc.getString(name+"_priority_policy");
		if(!forInserts) {
			offeredKeys = new OfferedKeysList[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
			for(short i=0;i<RequestStarter.NUMBER_OF_PRIORITY_CLASSES;i++)
				offeredKeys[i] = new OfferedKeysList(core, random, i, forSSKs);
		} else {
			offeredKeys = null;
		}
		if(!forInserts)
			transientCooldownQueue = new RequestCooldownQueue(COOLDOWN_PERIOD);
		else
			transientCooldownQueue = null;
		jobRunner = clientContext.jobRunner;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	public void start(NodeClientCore core) {
		schedCore.start(core);
		queueFillRequestStarterQueue();
	}
	
	/** Called by the  config. Callback
	 * 
	 * @param val
	 */
	protected synchronized void setPriorityScheduler(String val){
		choosenPriorityScheduler = val;
	}
	
	public void registerInsert(final SendableRequest req, boolean persistent, boolean regmeOnly) {
		registerInsert(req, persistent, regmeOnly, databaseExecutor.onThread());
	}

	static final int QUEUE_THRESHOLD = 100;
	
	public void registerInsert(final SendableRequest req, boolean persistent, boolean regmeOnly, boolean onDatabaseThread) {
		if(!isInsertScheduler)
			throw new IllegalArgumentException("Adding a SendableInsert to a request scheduler!!");
		if(persistent) {
			if(onDatabaseThread) {
				if(regmeOnly) {
					long bootID = 0;
					boolean queueFull = jobRunner.getQueueSize(NativeThread.NORM_PRIORITY) >= QUEUE_THRESHOLD;
					if(!queueFull)
						bootID = this.node.bootID;
					final RegisterMe regme = new RegisterMe(req, req.getPriorityClass(selectorContainer), schedCore, null, bootID);
					selectorContainer.set(regme);
					if(logMINOR)
						Logger.minor(this, "Added insert RegisterMe: "+regme);
					if(!queueFull) {
					jobRunner.queue(new DBJob() {
						
						public void run(ObjectContainer container, ClientContext context) {
							container.delete(regme);
							container.activate(req, 1);
							registerInsert(req, true, false, true);
							container.deactivate(req, 1);
						}
						
					}, NativeThread.NORM_PRIORITY, false);
					} else {
						schedCore.rerunRegisterMeRunner(jobRunner);
					}
					selectorContainer.deactivate(req, 1);
					return;
				}
				schedCore.innerRegister(req, random, selectorContainer);
			} else {
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						container.activate(req, 1);
						schedCore.innerRegister(req, random, selectorContainer);
					}
					
				}, NativeThread.NORM_PRIORITY, false);
			}
		} else {
			schedTransient.innerRegister(req, random, null);
		}
	}
	
	/**
	 * Register a group of requests (not inserts): a GotKeyListener and/or one 
	 * or more SendableGet's.
	 * @param listener Listeners for specific keys. Can be null if the listener
	 * is already registered e.g. most of the time with SplitFileFetcher*.
	 * @param getters The actual requests to register to the request sender queue.
	 * @param persistent True if the request is persistent.
	 * @param onDatabaseThread True if we are running on the database thread.
	 * NOTE: delayedStoreCheck/probablyNotInStore is unnecessary because we only
	 * register the listener once.
	 * @throws FetchException 
	 */
	public void register(final HasKeyListener hasListener, final SendableGet[] getters, final boolean persistent, boolean onDatabaseThread, final BlockSet blocks, final boolean noCheckStore) throws KeyListenerConstructionException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "register("+persistent+","+hasListener+","+getters);
		if(isInsertScheduler) {
			IllegalStateException e = new IllegalStateException("finishRegister on an insert scheduler");
			throw e;
		}
		if(persistent) {
			if(onDatabaseThread) {
				innerRegister(hasListener, getters, blocks, noCheckStore);
			} else {
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						// registerOffThread would be pointless because this is a separate job.
						if(hasListener != null)
							container.activate(hasListener, 1);
						if(getters != null) {
							for(int i=0;i<getters.length;i++)
								container.activate(getters[i], 1);
						}
						try {
							innerRegister(hasListener, getters, blocks, noCheckStore);
						} catch (KeyListenerConstructionException e) {
							Logger.error(this, "Registration failed to create Bloom filters: "+e+" on "+hasListener, e);
						}
						if(hasListener != null)
							container.deactivate(hasListener, 1);
						if(getters != null) {
							for(int i=0;i<getters.length;i++)
								container.deactivate(getters[i], 1);
						}
					}
					
				}, NativeThread.NORM_PRIORITY, false);
			}
		} else {
			final KeyListener listener;
			if(hasListener != null) {
				listener = hasListener.makeKeyListener(selectorContainer, clientContext);
				schedTransient.addPendingKeys(listener);
			} else
				listener = null;
			if(getters != null && !noCheckStore) {
				for(SendableGet getter : getters)
					datastoreChecker.queueTransientRequest(getter, blocks);
			} else {
				boolean anyValid = false;
				for(int i=0;i<getters.length;i++) {
					if(!(getters[i].isCancelled(null) || getters[i].isEmpty(null)))
						anyValid = true;
				}
				finishRegister(getters, false, onDatabaseThread, anyValid, null);
			}
		}
	}
	
	
	private void innerRegister(final HasKeyListener hasListener, final SendableGet[] getters, final BlockSet blocks, boolean noCheckStore) throws KeyListenerConstructionException {
		final KeyListener listener;
		if(hasListener != null) {
			listener = hasListener.makeKeyListener(selectorContainer, clientContext);
			schedCore.addPendingKeys(listener);
			selectorContainer.set(hasListener);
		} else
			listener = null;
		
		if(isInsertScheduler) {
			IllegalStateException e = new IllegalStateException("finishRegister on an insert scheduler");
			throw e;
		}
		if(!noCheckStore) {
			// Check the datastore before proceding.
			for(SendableGet getter : getters)
				datastoreChecker.queuePersistentRequest(getter, blocks, selectorContainer);
			selectorContainer.deactivate(listener, 1);
			if(getters != null) {
				for(int i=0;i<getters.length;i++)
					selectorContainer.deactivate(getters[i], 1);
			}
		} else {
			// We have already checked the datastore, this is a retry, the listener hasn't been unregistered.
			short prio = RequestStarter.MINIMUM_PRIORITY_CLASS;
			for(int i=0;i<getters.length;i++) {
				short p = getters[i].getPriorityClass(selectorContainer);
				if(p < prio) prio = p;
			}
			this.finishRegister(getters, true, true, true, null);
		}
	}

	void finishRegister(final SendableGet[] getters, boolean persistent, boolean onDatabaseThread, final boolean anyValid, final DatastoreCheckerItem reg) {
		if(isInsertScheduler && getters != null) {
			IllegalStateException e = new IllegalStateException("finishRegister on an insert scheduler");
			if(onDatabaseThread || !persistent) {
				for(int i=0;i<getters.length;i++) {
					if(persistent)
						selectorContainer.activate(getters[i], 1);
					getters[i].internalError(e, this, selectorContainer, clientContext, persistent);
					if(persistent)
						selectorContainer.deactivate(getters[i], 1);
				}
			}
			throw e;
		}
		if(persistent) {
			// Add to the persistent registration queue
			if(onDatabaseThread) {
				if(!databaseExecutor.onThread()) {
					throw new IllegalStateException("Not on database thread!");
				}
				if(persistent)
					selectorContainer.activate(getters, 1);
				if(logMINOR)
					Logger.minor(this, "finishRegister() for "+getters);
				if(anyValid) {
					boolean wereAnyValid = false;
					for(int i=0;i<getters.length;i++) {
						SendableGet getter = getters[i];
						selectorContainer.activate(getters[i], 1);
						if(!(getter.isCancelled(selectorContainer) || getter.isEmpty(selectorContainer))) {
							wereAnyValid = true;
							schedCore.innerRegister(getter, random, selectorContainer);
						}
					}
					if(!wereAnyValid) {
						Logger.normal(this, "No requests valid: "+getters);
					}
				}
				if(reg != null)
					selectorContainer.delete(reg);
				maybeFillStarterQueue(selectorContainer, clientContext);
				starter.wakeUp();
			} else {
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						container.activate(getters, 1);
						if(logMINOR)
							Logger.minor(this, "finishRegister() for "+getters);
						boolean wereAnyValid = false;
						for(int i=0;i<getters.length;i++) {
							SendableGet getter = getters[i];
							container.activate(getters[i], 1);
							if(!(getter.isCancelled(selectorContainer) || getter.isEmpty(selectorContainer))) {
								wereAnyValid = true;
								schedCore.innerRegister(getter, random, selectorContainer);
							}
							container.deactivate(getters[i], 1);
						}
						if(!wereAnyValid) {
							Logger.normal(this, "No requests valid: "+getters);
						}
						if(reg != null)
							container.delete(reg);
						maybeFillStarterQueue(container, context);
						starter.wakeUp();
					}
					
				}, NativeThread.NORM_PRIORITY+1, false);
			}
		} else {
			if(!anyValid) return;
			// Register immediately.
			for(int i=0;i<getters.length;i++)
				schedTransient.innerRegister(getters[i], random, null);
			starter.wakeUp();
		}
	}

	private void maybeFillStarterQueue(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(starterQueue.size() > MAX_STARTER_QUEUE_SIZE / 2)
				return;
		}
		requestStarterQueueFiller.run(container, context);
	}

	public ChosenBlock getBetterNonPersistentRequest(short prio, int retryCount) {
		short fuzz = -1;
		if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
			fuzz = -1;
		else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
			fuzz = 0;	
		return schedCore.removeFirst(fuzz, random, offeredKeys, starter, schedTransient, true, false, prio, retryCount, clientContext, null);
	}
	
	/**
	 * All the persistent SendableRequest's currently running (either actually in flight, just chosen,
	 * awaiting the callbacks being executed etc). Note that this is an ArrayList because we *must*
	 * compare by pointer: these objects may well implement hashCode() etc for use by other code, but 
	 * if they are deactivated, they will be unreliable. Fortunately, this will be fairly small most
	 * of the time, since a single SendableRequest might include 256 actual requests.
	 * 
	 * SYNCHRONIZATION: Synched on starterQueue.
	 */
	private final transient ArrayList<SendableRequest> runningPersistentRequests = new ArrayList<SendableRequest> ();
	
	public void removeRunningRequest(SendableRequest request) {
		synchronized(starterQueue) {
			for(int i=0;i<runningPersistentRequests.size();i++) {
				if(runningPersistentRequests.get(i) == request) {
					runningPersistentRequests.remove(i);
					i--;
				}
			}
		}
	}
	
	public boolean isRunningRequest(SendableRequest request) {
		synchronized(starterQueue) {
			for(int i=0;i<runningPersistentRequests.size();i++) {
				if(runningPersistentRequests.get(i) == request)
					return true;
			}
		}
		return false;
	}
	
	void startingRequest(SendableRequest request) {
		runningPersistentRequests.add(request);
	}
	
	/** The maximum number of requests that we will keep on the in-RAM request
	 * starter queue. */
	static final int MAX_STARTER_QUEUE_SIZE = 512; // two full segments
	
	/** The above doesn't include in-flight requests. In-flight requests will
	 * of course still have PersistentChosenRequest's in the database (on disk)
	 * even though they are not on the starter queue and so don't count towards
	 * the above limit. So we have a higher limit before we complain that 
	 * something odd is happening.. (e.g. leaking PersistentChosenRequest's). */
	static final int WARNING_STARTER_QUEUE_SIZE = 800;
	
	private transient LinkedList<PersistentChosenRequest> starterQueue = new LinkedList<PersistentChosenRequest>();
	
	/**
	 * Called by RequestStarter to find a request to run.
	 */
	public ChosenBlock grabRequest() {
		while(true) {
			PersistentChosenRequest reqGroup = null;
			synchronized(starterQueue) {
				short bestPriority = Short.MAX_VALUE;
				int bestRetryCount = Integer.MAX_VALUE;
				for(PersistentChosenRequest req : starterQueue) {
					if(req.prio < bestPriority || 
							(req.prio == bestPriority && req.retryCount < bestRetryCount)) {
						bestPriority = req.prio;
						bestRetryCount = req.retryCount;
						reqGroup = req;
					}
				}
			}
			if(reqGroup != null) {
				// Try to find a better non-persistent request
				ChosenBlock better = getBetterNonPersistentRequest(reqGroup.prio, reqGroup.retryCount);
				if(better != null) return better;
			}
			if(reqGroup == null) {
				queueFillRequestStarterQueue();
				return getBetterNonPersistentRequest(Short.MAX_VALUE, Integer.MAX_VALUE);
			}
			ChosenBlock block;
			int finalLength = 0;
			synchronized(starterQueue) {
				block = reqGroup.grabNotStarted(clientContext.fastWeakRandom, this);
				if(block == null) {
					for(int i=0;i<starterQueue.size();i++) {
						if(starterQueue.get(i) == reqGroup) {
							starterQueue.remove(i);
							if(logMINOR)
								Logger.minor(this, "Removed "+reqGroup+" from starter queue because is empty");
							i--;
						} else {
							finalLength += starterQueue.get(i).sizeNotStarted();
						}
					}
					continue;
				}
			}
			if(finalLength < MAX_STARTER_QUEUE_SIZE)
				queueFillRequestStarterQueue();
			if(logMINOR)
				Logger.minor(this, "grabRequest() returning "+block+" for "+reqGroup);
			return block;
		}
	}
	
	public void queueFillRequestStarterQueue() {
		if(starterQueueLength() > MAX_STARTER_QUEUE_SIZE / 2)
			return;
		jobRunner.queue(requestStarterQueueFiller, NativeThread.MAX_PRIORITY, true);
	}

	private int starterQueueLength() {
		int length = 0;
		synchronized(starterQueue) {
			for(PersistentChosenRequest request : starterQueue)
				length += request.sizeNotStarted();
		}
		return length;
	}

	/**
	 * @param request
	 * @param container
	 * @return True if the queue is now full/over-full.
	 */
	boolean addToStarterQueue(SendableRequest request, ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "Adding to starter queue: "+request);
		container.activate(request, 1);
		PersistentChosenRequest chosen = new PersistentChosenRequest(request, request.getPriorityClass(container), request.getRetryCount(), container, ClientRequestScheduler.this, clientContext);
		if(logMINOR)
			Logger.minor(this, "Created PCR: "+chosen);
		container.deactivate(request, 1);
		synchronized(starterQueue) {
			// Since we pass in runningPersistentRequests, we don't need to check whether it is already in the starterQueue.
			starterQueue.add(chosen);
			int length = starterQueueLength();
			length += chosen.sizeNotStarted();
			runningPersistentRequests.add(request);
			return length < MAX_STARTER_QUEUE_SIZE;
		}
	}
	
	int starterQueueSize() {
		synchronized(starterQueue) {
			return starterQueue.size();
		}
	}
	
	/** Maximum number of requests to select from a single SendableRequest */
	final int MAX_CONSECUTIVE_SAME_REQ = 50;
	
	private DBJob requestStarterQueueFiller = new DBJob() {
		public void run(ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "Filling request queue... (SSK="+isSSKScheduler+" insert="+isInsertScheduler);
			short fuzz = -1;
			if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
				fuzz = -1;
			else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
				fuzz = 0;	
			synchronized(starterQueue) {
				// Recompute starterQueueLength
				int length = 0;
				for(PersistentChosenRequest req : starterQueue) {
					req.pruneDuplicates(ClientRequestScheduler.this);
					length += req.sizeNotStarted();
				}
				if(logMINOR) Logger.minor(this, "Queue size: "+length+" SSK="+isSSKScheduler+" insert="+isInsertScheduler);
				if(length >= MAX_STARTER_QUEUE_SIZE) {
					if(length >= WARNING_STARTER_QUEUE_SIZE)
						Logger.error(this, "Queue already full: "+starterQueue.size());
					return;
				}
				if(length > MAX_STARTER_QUEUE_SIZE * 3 / 4) {
					return;
				}
			}
			
			while(true) {
				SendableRequest request = schedCore.removeFirstInner(fuzz, random, offeredKeys, starter, schedTransient, false, true, Short.MAX_VALUE, Integer.MAX_VALUE, context, container);
				if(request == null) return;
				boolean full = addToStarterQueue(request, container);
				starter.wakeUp();
				if(full) return;
				return;
			}
		}
	};
	
	/**
	 * Compare a recently registered SendableRequest to what is already on the
	 * starter queue. If it is better, kick out stuff from the queue until we
	 * are just over the limit.
	 * @param req
	 * @param container
	 */
	public void maybeAddToStarterQueue(SendableRequest req, ObjectContainer container) {
		short prio = req.getPriorityClass(container);
		int retryCount = req.getRetryCount();
		synchronized(starterQueue) {
			boolean allBetter = true;
			boolean betterThanSome = false;
			int size = 0;
			for(PersistentChosenRequest old : starterQueue) {
				size += old.sizeNotStarted();
				if(old.prio < prio)
					allBetter = false;
				else if(old.prio == prio && old.retryCount <= retryCount)
					allBetter = false;
				if(old.prio > prio || old.prio == prio && old.prio > retryCount)
					betterThanSome = true;
			}
			if(allBetter && !starterQueue.isEmpty()) return;
			if(size >= MAX_STARTER_QUEUE_SIZE && !betterThanSome) return;
		}
		addToStarterQueue(req, container);
		trimStarterQueue(container);
	}
	
	private void trimStarterQueue(ObjectContainer container) {
		ArrayList<PersistentChosenRequest> dumped = null;
		synchronized(starterQueue) {
			int length = starterQueueLength();
			while(length > MAX_STARTER_QUEUE_SIZE) {
				// Find the lowest priority/retry count request.
				// If we can dump it without going below the limit, then do so.
				// If we can't, return.
				PersistentChosenRequest worst = null;
				short worstPrio = -1;
				int worstRetryCount = -1;
				int worstIndex = -1;
				int worstLength = -1;
				if(starterQueue.isEmpty()) {
					break;
				}
				length = 0;
				for(int i=0;i<starterQueue.size();i++) {
					PersistentChosenRequest req = starterQueue.get(i);
					short prio = req.prio;
					int retryCount = req.retryCount;
					int size = req.sizeNotStarted();
					length += size;
					if(prio > worstPrio ||
							(prio == worstPrio && retryCount > worstRetryCount)) {
						worstPrio = prio;
						worstRetryCount = retryCount;
						worst = req;
						worstIndex = i;
						worstLength = size;
						continue;
					}
				}
				int lengthAfter = length - worstLength;
				if(lengthAfter >= MAX_STARTER_QUEUE_SIZE) {
					if(dumped == null)
						dumped = new ArrayList<PersistentChosenRequest>(2);
					dumped.add(worst);
					starterQueue.remove(worstIndex);
					if(lengthAfter == MAX_STARTER_QUEUE_SIZE) break;
				} else {
					// Can't remove any more.
					break;
				}
			}
		}
		if(dumped == null) return;
		for(PersistentChosenRequest req : dumped) {
			req.onDumped(schedCore, container);
		}
	}

	/**
	 * Remove a SendableGet from the list of getters we maintain for each key, indicating that we are no longer interested
	 * in that key.
	 * @param getter
	 * @param complain
	 */
	public void removePendingKeys(KeyListener getter, boolean complain) {
		boolean found = schedTransient.removePendingKeys(getter);
		found |= schedCore.removePendingKeys(getter);
		if(complain && !found)
			Logger.error(this, "Listener not found when removing: "+getter);
	}

	/**
	 * Remove a SendableGet from the list of getters we maintain for each key, indicating that we are no longer interested
	 * in that key.
	 * @param getter
	 * @param complain
	 */
	public void removePendingKeys(HasKeyListener getter, boolean complain) {
		boolean found = schedTransient.removePendingKeys(getter);
		found |= schedCore.removePendingKeys(getter);
		if(complain && !found)
			Logger.error(this, "Listener not found when removing: "+getter);
	}

	public void reregisterAll(final ClientRequester request, ObjectContainer container) {
		schedTransient.reregisterAll(request, random, this, null, clientContext);
		schedCore.reregisterAll(request, random, this, container, clientContext);
		starter.wakeUp();
	}
	
	public String getChoosenPriorityScheduler() {
		return choosenPriorityScheduler;
	}

	/*
	 * tripPendingKey() callbacks must run quickly, since we've found a block.
	 * succeeded() must run quickly, since we delete the PersistentChosenRequest.
	 * tripPendingKey() must run before succeeded() so we don't choose the same
	 * request again, then remove it from pendingKeys before it completes! 
	 */
	static final short TRIP_PENDING_PRIORITY = NativeThread.HIGH_PRIORITY-1;
	
	public synchronized void succeeded(final BaseSendableGet succeeded, final ChosenBlock req) {
		if(req.isPersistent()) {
			jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					container.activate(succeeded, 1);
					schedCore.succeeded(succeeded, container);
					container.deactivate(succeeded, 1);
				}
				
			}, TRIP_PENDING_PRIORITY, false);
			// Boost the priority so the PersistentChosenRequest gets deleted reasonably quickly.
		} else
			schedTransient.succeeded(succeeded, null);
	}

	public void tripPendingKey(final KeyBlock block) {
		if(logMINOR) Logger.minor(this, "tripPendingKey("+block.getKey()+")");
		
		if(offeredKeys != null) {
			for(int i=0;i<offeredKeys.length;i++) {
				offeredKeys[i].remove(block.getKey());
			}
		}
		final Key key = block.getKey();
		schedTransient.tripPendingKey(key, block, null, clientContext);
		if(schedCore.anyProbablyWantKey(key, clientContext)) {
			jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					if(logMINOR) Logger.minor(this, "tripPendingKey for "+key);
					schedCore.tripPendingKey(key, block, container, clientContext);
				}
			}, TRIP_PENDING_PRIORITY, false);
		}
		
	}

	/** If we want the offered key, or if force is enabled, queue it */
	public void maybeQueueOfferedKey(final Key key, boolean force) {
		if(logMINOR)
			Logger.minor(this, "maybeQueueOfferedKey("+key+","+force);
		short priority = Short.MAX_VALUE;
		if(force) {
			// FIXME what priority???
			priority = RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
		}
		priority = schedTransient.getKeyPrio(key, priority, null, clientContext);
		if(priority < Short.MAX_VALUE) {
			offeredKeys[priority].queueKey(key);
			starter.wakeUp();
		}
		
		final short oldPrio = priority;
		
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				// Don't activate/deactivate the key, because it's not persistent in the first place!!
				short priority = schedCore.getKeyPrio(key, oldPrio, container, context);
				if(priority >= oldPrio) return; // already on list at >= priority
				offeredKeys[priority].queueKey(key.cloneKey());
				starter.wakeUp();
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}

	public void dequeueOfferedKey(Key key) {
		for(int i=0;i<offeredKeys.length;i++) {
			offeredKeys[i].remove(key);
		}
	}

	/**
	 * MUST be called from database thread!
	 */
	public long queueCooldown(ClientKey key, SendableGet getter) {
		if(getter.persistent())
			return persistentCooldownQueue.add(key.getNodeKey(), getter, selectorContainer);
		else
			return transientCooldownQueue.add(key.getNodeKey(), getter, null);
	}

	private final DBJob moveFromCooldownJob = new DBJob() {
		
		public void run(ObjectContainer container, ClientContext context) {
			if(moveKeysFromCooldownQueue(persistentCooldownQueue, true, selectorContainer) ||
					moveKeysFromCooldownQueue(transientCooldownQueue, false, selectorContainer))
				starter.wakeUp();
		}
		
	};
	
	public void moveKeysFromCooldownQueue() {
		jobRunner.queue(moveFromCooldownJob, NativeThread.NORM_PRIORITY, true);
	}
	
	private boolean moveKeysFromCooldownQueue(CooldownQueue queue, boolean persistent, ObjectContainer container) {
		if(queue == null) return false;
		long now = System.currentTimeMillis();
		/*
		 * Only go around once. We will be called again. If there are keys to move, then RequestStarter will not
		 * sleep, because it will start them. Then it will come back here. If we are off-thread i.e. on the database
		 * thread, then we will wake it up if we find keys... and we'll be scheduled again.
		 * 
		 * FIXME: I think we need to restore all the listeners for a single key 
		 * simultaneously to avoid some kind of race condition? Or could we just
		 * restore the one request on the queue? Maybe it's just a misguided
		 * optimisation? IIRC we had some severe problems when we didn't have 
		 * this, related to requests somehow being lost altogether... Is it 
		 * essential? We can save a query if it's not... Is this about requests
		 * or about keys? Should we limit all requests across any 
		 * SendableRequest's to 3 every half hour for a specific key? Probably 
		 * yes...? In which case, can the cooldown queue be entirely in RAM,
		 * and would it be useful for it to be? Less disk, more RAM... for fast
		 * nodes with little RAM it would be bad...
		 */
		final int MAX_KEYS = 20;
		Key[] keys = queue.removeKeyBefore(now, container, MAX_KEYS);
		if(keys == null) return false;
		for(int j=0;j<keys.length;j++) {
			Key key = keys[j];
			if(persistent)
				container.activate(key, 5);
			if(logMINOR) Logger.minor(this, "Restoring key: "+key);
			SendableGet[] reqs = schedCore.requestsForKey(key, container, clientContext);
			SendableGet[] transientReqs = schedTransient.requestsForKey(key, container, clientContext);
			if(reqs == null && transientReqs == null) {
				// Not an error as this can happen due to race conditions etc.
				if(logMINOR) Logger.minor(this, "Restoring key but no keys queued?? for "+key);
			}
			if(reqs != null) {
				for(int i=0;i<reqs.length;i++)
					reqs[i].requeueAfterCooldown(key, now, container, clientContext);
			}
			if(transientReqs != null) {
				for(int i=0;i<reqs.length;i++)
					transientReqs[i].requeueAfterCooldown(key, now, container, clientContext);
			}
			if(persistent)
				container.deactivate(key, 5);
		}
		return true;
	}

	public long countTransientQueuedRequests() {
		return schedTransient.countQueuedRequests(null);
	}

	public KeysFetchingLocally fetchingKeys() {
		return schedCore;
	}

	public void removeFetchingKey(Key key) {
		schedCore.removeFetchingKey(key);
	}
	
	/**
	 * Map from SendableGet implementing SupportsBulkCallFailure to BulkCallFailureItem[].
	 */
	private transient HashMap bulkFailureLookupItems = new HashMap();
	private transient HashMap bulkFailureLookupJob = new HashMap();

	public void callFailure(final SendableGet get, final LowLevelGetException e, int prio, boolean persistent) {
		if(!persistent) {
			get.onFailure(e, null, null, clientContext);
		} else {
			jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					get.onFailure(e, null, container, clientContext);
				}
				
			}, prio, false);
		}
	}
	
	public void callFailure(final SendableInsert insert, final LowLevelPutException e, int prio, boolean persistent) {
		if(!persistent) {
			insert.onFailure(e, null, null, clientContext);
		} else {
			jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					insert.onFailure(e, null, container, context);
				}
				
			}, prio, false);
		}
	}
	
	public FECQueue getFECQueue() {
		return clientContext.fecQueue;
	}

	public ClientContext getContext() {
		return clientContext;
	}

	/**
	 * @return True unless the key was already present.
	 */
	public boolean addToFetching(Key key) {
		return schedCore.addToFetching(key);
	}
	
	public boolean hasFetchingKey(Key key) {
		return schedCore.hasKey(key);
	}

	public long countPersistentQueuedRequests(ObjectContainer container) {
		return schedCore.countQueuedRequests(container);
	}

	public boolean isQueueAlmostEmpty() {
		return starterQueueSize() < MAX_STARTER_QUEUE_SIZE / 4;
	}
	
	public boolean isInsertScheduler() {
		return isInsertScheduler;
	}

	public void removeFromAllRequestsByClientRequest(ClientRequester clientRequest, SendableRequest get, boolean dontComplain) {
		if(get.persistent())
			schedCore.removeFromAllRequestsByClientRequest(get, clientRequest, dontComplain, selectorContainer);
		else
			schedTransient.removeFromAllRequestsByClientRequest(get, clientRequest, dontComplain, null);
	}

	public byte[] saltKey(Key key) {
		MessageDigest md = SHA256.getMessageDigest();
		md.update(key.getRoutingKey());
		md.update(schedCore.globalSalt);
		byte[] ret = md.digest();
		SHA256.returnMessageDigest(md);
		return ret;
	}
	
}
