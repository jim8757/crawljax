package com.crawljax.core.state;

import java.io.Serializable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.math.stat.descriptive.moment.Mean;
import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.graph.DirectedMultigraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crawljax.core.ExitNotifier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

/**
 * The State-Flow Graph is a multi-edge directed graph with states (StateVetex) on the vertices and
 * clickables (Eventable) on the edges.
 */
@Singleton
@SuppressWarnings("serial")
public class InMemoryStateFlowGraph implements Serializable, StateFlowGraph {

	private static final Logger LOG = LoggerFactory.getLogger(InMemoryStateFlowGraph.class
	        .getName());

	private final DirectedGraph<StateVertex, Eventable> sfg;
	private final Lock readLock;
	private final Lock writeLock;

	/**
	 * Intermediate counter for the number of states, not relaying on getAllStates.size() because of
	 * Thread-safety.
	 */
	private final AtomicInteger stateCounter = new AtomicInteger();
	private final AtomicInteger nextStateNameCounter = new AtomicInteger();
	private final ConcurrentMap<Integer, StateVertex> stateById;
	private final ExitNotifier exitNotifier;

	/**
	 * The constructor.
	 * 
	 * @param initialState
	 *            the state to start from.
	 */
	@Inject
	public InMemoryStateFlowGraph(ExitNotifier exitNotifier) {
		this.exitNotifier = exitNotifier;
		sfg = new DirectedMultigraph<>(Eventable.class);
		stateById = Maps.newConcurrentMap();
		LOG.debug("Initialized the stateflowgraph");
		ReadWriteLock lock = new ReentrantReadWriteLock();
		readLock = lock.readLock();
		writeLock = lock.writeLock();
	}

	/**
	 * Adds a state (as a vertix) to the State-Flow Graph if not already present. More formally,
	 * adds the specified vertex, v, to this graph if this graph contains no vertex u such that
	 * u.equals(v). If this graph already contains such vertex, the call leaves this graph unchanged
	 * and returns false. In combination with the restriction on constructors, this ensures that
	 * graphs never contain duplicate vertices. Throws java.lang.NullPointerException - if the
	 * specified vertex is null. This method automatically updates the state name to reflect the
	 * internal state counter.
	 * 
	 * @param stateVertix
	 *            the state to be added.
	 * @return the clone if one is detected null otherwise.
	 * @see org.jgrapht.Graph#addVertex(Object)
	 */
	public StateVertex putIfAbsent(StateVertex stateVertix) {
		return putIfAbsent(stateVertix, true);
	}

	public StateVertex putIndex(StateVertex index) {
		return putIfAbsent(index, false);
	}

	/**
	 * Adds a state (as a vertix) to the State-Flow Graph if not already present. More formally,
	 * adds the specified vertex, v, to this graph if this graph contains no vertex u such that
	 * u.equals(v). If this graph already contains such vertex, the call leaves this graph unchanged
	 * and returns false. In combination with the restriction on constructors, this ensures that
	 * graphs never contain duplicate vertices. Throws java.lang.NullPointerException - if the
	 * specified vertex is null.
	 * 
	 * @param stateVertix
	 *            the state to be added.
	 * @param correctName
	 *            if true the name of the state will be corrected according to the internal state
	 *            counter.
	 * @return the clone if one is detected <code>null</code> otherwise.
	 * @see org.jgrapht.Graph#addVertex(Object)
	 */
	private StateVertex putIfAbsent(StateVertex stateVertix, boolean correctName) {
		writeLock.lock();
		try {
			boolean added = sfg.addVertex(stateVertix);
			if (added) {
				stateById.put(stateVertix.getId(), stateVertix);
				int count = stateCounter.incrementAndGet();
				exitNotifier.incrementNumberOfStates();
				LOG.debug("Number of states is now {}", count);
				return null;
			} else {
				// Graph already contained the vertex
				LOG.debug("Graph already contained vertex {}", stateVertix);
				return this.getStateInGraph(stateVertix);
			}
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public StateVertex getById(int id) {
		return stateById.get(id);
	}

	@Override
	public StateVertex getInitialState() {
		return stateById.get(StateVertex.INDEX_ID);
	}

	/**
	 * Adds the specified edge to this graph, going from the source vertex to the target vertex.
	 * More formally, adds the specified edge, e, to this graph if this graph contains no edge e2
	 * such that e2.equals(e). If this graph already contains such an edge, the call leaves this
	 * graph unchanged and returns false. Some graphs do not allow edge-multiplicity. In such cases,
	 * if the graph already contains an edge from the specified source to the specified target, than
	 * this method does not change the graph and returns false. If the edge was added to the graph,
	 * returns true. The source and target vertices must already be contained in this graph. If they
	 * are not found in graph IllegalArgumentException is thrown.
	 * 
	 * @param sourceVert
	 *            source vertex of the edge.
	 * @param targetVert
	 *            target vertex of the edge.
	 * @param clickable
	 *            the clickable edge to be added to this graph.
	 * @return true if this graph did not already contain the specified edge.
	 * @see org.jgrapht.Graph#addEdge(Object, Object, Object)
	 */
	public boolean addEdge(StateVertex sourceVert, StateVertex targetVert, Eventable clickable) {
		writeLock.lock();
		try {
			if (sfg.containsEdge(sourceVert, targetVert)
			        && sfg.getAllEdges(sourceVert, targetVert).contains(clickable)) {
				return false;
			} else {
				return sfg.addEdge(sourceVert, targetVert, clickable);
			}
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public String toString() {
		readLock.lock();
		try {
			return sfg.toString();
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public ImmutableSet<Eventable> getOutgoingClickables(StateVertex stateVertix) {
		readLock.lock();
		try {
			return ImmutableSet.copyOf(sfg.outgoingEdgesOf(stateVertix));
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public ImmutableSet<Eventable> getIncomingClickable(StateVertex stateVertix) {
		readLock.lock();
		try {
			return ImmutableSet.copyOf(sfg.incomingEdgesOf(stateVertix));
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public boolean canGoTo(StateVertex source, StateVertex target) {
		readLock.lock();
		try {
			return sfg.containsEdge(source, target) || sfg.containsEdge(target, source);
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public ImmutableList<Eventable> getShortestPath(StateVertex start, StateVertex end) {
		readLock.lock();
		try {
			return ImmutableList.copyOf(DijkstraShortestPath.findPathBetween(sfg, start, end));
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public ImmutableSet<StateVertex> getAllStates() {
		readLock.lock();
		try {
			return ImmutableSet.copyOf(sfg.vertexSet());
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public ImmutableSet<Eventable> getAllEdges() {
		readLock.lock();
		try {
			return ImmutableSet.copyOf(sfg.edgeSet());
		} finally {
			readLock.unlock();
		}
	}

	private StateVertex getStateInGraph(StateVertex state) {
		readLock.lock();
		try {
			for (StateVertex st : sfg.vertexSet()) {
				if (state.equals(st)) {
					return st;
				}
			}
			return null;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public int getMeanStateStringSize() {
		readLock.lock();
		try {
			final Mean mean = new Mean();

			for (StateVertex state : sfg.vertexSet()) {
				mean.increment(state.getDom().getBytes().length);
			}

			return (int) mean.getResult();
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public int getNumberOfStates() {
		return stateCounter.get();
	}

	StateVertex newStateFor(String url, String dom, String strippedDom) {
		int id = nextStateNameCounter.incrementAndGet();
		return new StateVertex(id, url, getNewStateName(id), dom, strippedDom);
	}

	private String getNewStateName(int id) {
		return "state" + id;
	}

}
