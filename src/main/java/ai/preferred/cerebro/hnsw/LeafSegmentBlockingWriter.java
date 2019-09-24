package ai.preferred.cerebro.hnsw;


import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;

//A leaf hnsw index writer can insert around 9M ~ 10M nodes before insert time start to increase tremendously
//Never insert more than 5M nodes into a leaf in one go, it will cost you a lot of time as insert time increase
// exponentially with current number of nodes in the graph.

//Instead use HnswIndexWriter to spread your vectors out in many graphs, the number of graphs to spread out your nodes
//should be the number of cores your CPU has. This will give you both good index building time, good search time and
// nearly double the accuracy (tested with a 6-core CPU).
public class LeafSegmentBlockingWriter extends LeafSegmentWriter {

    private ReentrantLock globalLock;
    private StampedLock stampedLock;
    private BitSet activeConstruction;
    private AtomicReferenceArray<Node> nodes;

    //Create constructor
    public LeafSegmentBlockingWriter(HnswIndexWriter parent, int numName, int base) {
        super(parent, numName, base);
        this.globalLock = new ReentrantLock();
        this.stampedLock = new StampedLock();
        this.activeConstruction = new BitSet(this.maxNodeCount);
        nodes = new AtomicReferenceArray<>(super.nodes);
        super.nodes = null;
    }

    //Load constructor
    public LeafSegmentBlockingWriter(HnswIndexWriter parent, int numName , String idxDir) {
        super(parent, numName, idxDir);

        this.globalLock = new ReentrantLock();
        this.stampedLock = new StampedLock();
        this.activeConstruction = new BitSet(this.maxNodeCount);
        nodes = new AtomicReferenceArray<>(super.nodes);
        super.nodes = null;
    }
    @Override
    public Optional<double[]> getVec(int internalID) {
        return Optional.ofNullable(nodes.get(internalID)).map(Node::vector);
    }
    @Override
    public Optional<Node> getNode(int internalID) {
        return Optional.ofNullable(nodes.get(internalID));
    }

    @Override
    public boolean removeOnInternalID(int internalID) {
        if (!removeEnabled) {
            return false;
        }
        globalLock.lock();
        try {

            Node node = nodes.get(internalID);

            for (int level = node.maxLevel(); level >= 0; level--) {
                final int thisLevel = level;
                node.inConns[level].forEach(neighbourId ->
                        nodes.get(neighbourId).outConns[thisLevel].remove(internalID));

                node.outConns[level].forEach(neighbourId ->
                        nodes.get(neighbourId).inConns[thisLevel].remove(internalID));
            }

            // change the entry point to the first outgoing connection at the highest level
            if (entryPoint == node) {
                for (int level = node.maxLevel(); level >= 0; level--) {
                    IntArrayList outgoingConnections = node.outConns[level];
                    if (!outgoingConnections.isEmpty()) {
                        entryPoint = nodes.get(outgoingConnections.getFirst());
                        break;
                    }
                }

            }

            // if we could not change the outgoing connection it means we are the last node
            if (entryPoint == node) {
                entryPoint = null;
            }
            if(lookup.contains(node.item.externalId))
                lookup.remove(node.item.externalId);
            nodes.set(internalID, null);

            //no need to put freedIds inside a synchronized block because
            //other other code sections that write to freedIds are also inside
            //global lock
            freedIds.push(internalID);
        }
        finally { globalLock.unlock(); }

        return true;
    }

    //to be handled by parent


    /**
     * @param item the item to add to the index
     * @return true means item added successfully,
     */
    @Override
    public boolean add(Item item) {
        globalLock.lock();
        try {
            Integer globalId = lookup.get(item.externalId);


            //check if there is nodes with similar id in the graph
            if(globalId != null){
                //if there is similar id but index does not support removal then abort operation
                if (!removeEnabled) {
                    return false;
                }
                //if there is already this id in the index, it means this is an update
                //so only handle if this is the leaf that the id was already residing
                if(globalId >= baseID && globalId < baseID + maxNodeCount){
                    Node node = nodes.get(globalId - baseID);
                    if (Objects.deepEquals(node.vector(), item.vector)) {
                        //object already added
                        return true;
                    } else {
                        //similar id but different vector means different object
                        //so remove the object to insert the current new one
                        removeOnInternalID(item.externalId);
                    }
                }
                else
                    return false;

            }
            int internalId;
            //try to use used id of deleted node to assign to this new node
            //if not available use a new id (unconsumed) for this node
            if (freedIds.isEmpty()) {
                if (nodeCount >= this.maxNodeCount) {
                    return false;
                }
                internalId = nodeCount++;
            } else {
                internalId = freedIds.pop();
            }

            //randomize level
            int randomLevel = assignLevel(item.externalId, this.levelLambda);

            IntArrayList[] outConns = new IntArrayList[randomLevel + 1];

            for (int level = 0; level <= randomLevel; level++) {
                int levelM = randomLevel == 0 ? maxM0 : maxM;
                outConns[level] = new IntArrayList(levelM);
            }

            IntArrayList[] inConns = removeEnabled ? new IntArrayList[randomLevel + 1] : null;
            if (removeEnabled) {
                for (int level = 0; level <= randomLevel; level++) {
                    int levelM = randomLevel == 0 ? maxM0 : maxM;
                    inConns[level] = new IntArrayList(levelM);
                }
            }

            Node entryPointCopy = entryPoint;

            if (entryPoint != null && randomLevel <= entryPoint.maxLevel()) {
                globalLock.unlock();
            }

            //if the global lock is released above,
            //then basically at this point forward
            //there is no lock at all, since this is a read lock.
            //Unless a write lock has been called somewhere.
            long stamp = stampedLock.readLock();

            try {

                //the bitset is shared across all threads to signal
                //setting aside nodes that are being inserted

                synchronized (activeConstruction) {

                    activeConstruction.flipTrue(internalId);
                }

                Node newNode = new Node(internalId, outConns, inConns, item);
                nodes.set(internalId, newNode);
                lookup.put(item.externalId, internalId + baseID);

                Node curNode = entryPointCopy;

                //entry point is null if this is the first node inserted into the graph
                if (curNode != null) {

                    //if no layer added
                    if (newNode.maxLevel() < entryPointCopy.maxLevel()) {

                        double curDist = distanceFunction.distance(newNode.vector(), curNode.vector());
                        //sequentially zoom in until reach the layer next to
                        // the highest layer that the new node has to be inserted
                        for (int curLevel = entryPointCopy.maxLevel(); curLevel > newNode.maxLevel(); curLevel--) {

                            boolean changed = true;
                            while (changed){
                                changed = false;
                                IntArrayList candidateConns = curNode.outConns[curLevel];
                                synchronized (candidateConns) {


                                    for (int i = 0; i < candidateConns.size(); i++) {

                                        int candidateId = candidateConns.get(i);

                                        Node candidateNode = nodes.get(candidateId);

                                        double candidateDistance = distanceFunction.distance(newNode.vector(), candidateNode.vector());

                                        //updating the starting node to be used at lower level
                                        if (lesser(candidateDistance, curDist)) {
                                            curDist = candidateDistance;
                                            curNode = candidateNode;
                                            changed = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    //insert the new node starting from its highest layer by setting up connections
                    for (int level = Math.min(randomLevel, entryPointCopy.maxLevel()); level >= 0; level--) {
                        RestrictedMaxHeap topCandidates = searchLayer(curNode, newNode.vector(), efConstruction, level);
                        synchronized (newNode) {
                            mutuallyConnectNewElement(newNode, topCandidates, level);
                        }

                    }
                }

                // if this is the first node inserted or its highest layer is higher than that
                // of the current entry node, then we have to update the entry node
                if (entryPoint == null || newNode.maxLevel() > entryPointCopy.maxLevel()) {
                    // this is thread safe because we get the global lock when we add a level
                    this.entryPoint = newNode;
                }
            } finally {
                //upon insert completion signal that the node is ready
                //to take part in the insertion of other nodes

                synchronized (activeConstruction) {
                    activeConstruction.flipFalse(internalId);
                }

                stampedLock.unlockRead(stamp);
                return true;
            }
        } finally {
            //this code section is called when this node insertion
            //has updated the entry node of the graph.
            if (globalLock.isHeldByCurrentThread()) {
                globalLock.unlock();
            }
        }
    }

    @Override
    protected void mutuallyConnectNewElement(Node newNode,
                                           RestrictedMaxHeap topCandidates,
                                           int level) {

        int bestN = level == 0 ? this.maxM0 : this.maxM;

        int newNodeId = newNode.internalId;
        double[] newNodeVector = newNode.vector();
        IntArrayList outNewNodeConns = newNode.outConns[level];

        Iterator<Candidate> iteratorSelected = getNeighborsByHeuristic2(topCandidates, null, bestN);
        while (iteratorSelected.hasNext()){
        //for (Candidate selected: selectedNeighbors) {
            int selectedNeighbourId = iteratorSelected.next().nodeId;

            synchronized (activeConstruction) {
                if (activeConstruction.isTrue(selectedNeighbourId)) {
                    continue;
                }
            }
            outNewNodeConns.add(selectedNeighbourId);

            Node neighbourNode = nodes.get(selectedNeighbourId);

            synchronized (neighbourNode) {

                if (removeEnabled) {
                    neighbourNode.inConns[level].add(newNodeId);
                }

                double[] neighbourVector = neighbourNode.vector();

                IntArrayList outNeighbourConnsAtLevel = neighbourNode.outConns[level];

                if (outNeighbourConnsAtLevel.size() < bestN) {
                    if (removeEnabled) {
                        newNode.inConns[level].add(selectedNeighbourId);
                    }
                    outNeighbourConnsAtLevel.add(newNodeId);
                } else {
                    // finding the "weakest" element to replace it with the new one
                    double dMax = distanceFunction.distance(newNodeVector, neighbourNode.vector());
                    RestrictedMaxHeap candidates = new RestrictedMaxHeap(bestN + 1, ()-> null);
                    candidates.add(new Candidate(newNodeId, dMax, distanceComparator));
                    outNeighbourConnsAtLevel.forEach(id -> {
                        double dist = distanceFunction.distance(neighbourVector, nodes.get(id).vector());
                        candidates.add(new Candidate(id, dist, distanceComparator));
                    });

                    if (removeEnabled) {
                        newNode.inConns[level].add(selectedNeighbourId);
                    }

                    //I don't think we need more robustness at this point as the set is now reduced
                    //to bestN + 1 already, and we need to pick out the top bestN. The difference of
                    //one candidate doesn't justify calling the costly getNeighborsByHeuristic2() !
                    Candidate rejected = candidates.pop();

                    outNeighbourConnsAtLevel.clear();
                    Iterator<Candidate> iterator = candidates.iterator();
                    while (iterator.hasNext()){
                        outNeighbourConnsAtLevel.add(iterator.next().nodeId);
                    }

                    if (removeEnabled) {
                        Node node = nodes.get(rejected.nodeId);
                        node.inConns[level].remove(selectedNeighbourId);
                    }

                }
            }
        }

    }

    @Override
    protected RestrictedMaxHeap searchLayer(
            Node entryPointNode, double[] destination, int k, int layer) {

        BitSet visitedBitSet = parent.getBitsetFromPool();

        try {
            RestrictedMaxHeap topCandidates =
                    new RestrictedMaxHeap(k, ()-> null);
            PriorityQueue<Candidate> checkNeighborSet = new PriorityQueue<>();

            double distance = distanceFunction.distance(destination, entryPointNode.vector());

            Candidate firstCandidade = new Candidate(entryPointNode.internalId, distance, distanceComparator);

            topCandidates.add(firstCandidade);
            checkNeighborSet.add(firstCandidade);
            visitedBitSet.flipTrue(entryPointNode.internalId);

            double lowerBound = distance;

            while (!checkNeighborSet.isEmpty()) {

                Candidate curCandidate = checkNeighborSet.poll();

                if (greater(curCandidate.distance, lowerBound)) {
                    break;
                }

                Node node = nodes.get(curCandidate.nodeId);

                synchronized (node) {

                    MutableIntList candidates = node.outConns[layer];

                    for (int i = 0; i < candidates.size(); i++) {

                        int candidateId = candidates.get(i);

                        if (!visitedBitSet.isTrue(candidateId)) {

                            visitedBitSet.flipTrue(candidateId);

                            double candidateDistance = distanceFunction.distance(destination,
                                    nodes.get(candidateId).vector());

                            if (greater(topCandidates.top().distance, candidateDistance) || topCandidates.size() < k) {

                                Candidate newCandidate = new Candidate(candidateId, candidateDistance, distanceComparator);

                                checkNeighborSet.add(newCandidate);
                                if (topCandidates.size() == k)
                                    topCandidates.updateTop(newCandidate);
                                else
                                    topCandidates.add(newCandidate);

                                lowerBound = topCandidates.top().distance;
                            }
                        }
                    }

                }
            }

            return topCandidates;
        } finally {
            visitedBitSet.clear();
            parent.returnBitsetToPool(visitedBitSet);
        }
    }
    @Override
    protected void saveVecs(String dirPath)  {
        synchronized(nodes){
            double[][] vecs = new  double[nodeCount][];
            Node t;
            for (int i = 0; i < nodeCount; i++) {
                t = this.nodes.get(i);
                if (t != null)
                    vecs[i] = this.nodes.get(i).vector();
                else vecs[i] = null;
            }
            Kryo kryo = new Kryo();
            kryo.register(double[].class);
            kryo.register(double[][].class);

            try {
                Output outputVec = new Output(new FileOutputStream(dirPath + LOCAL_VECS));
                kryo.writeObject(outputVec, vecs);
                outputVec.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }
    @Override
    protected void saveOutConns(String dirPath) {
        synchronized(nodes){
            int[][][] outConns = new int[nodeCount][][];
            Node t;
            for (int i = 0; i < nodeCount; i++) {
                t = this.nodes.get(i);
                if(t != null){
                    outConns[i] = new int[t.outConns.length][];
                    for (int j = 0; j < t.outConns.length; j++) {
                        outConns[i][j] = t.outConns[j].toArray();
                    }
                }
                else outConns[i] = null;
            }
            Kryo kryo = new Kryo();
            kryo.register(int[].class);
            kryo.register(int[][].class);
            kryo.register(int[][][].class);
            try {
                Output outputOutconns = new Output(new FileOutputStream(dirPath + LOCAL_OUTCONN));
                kryo.writeObject(outputOutconns, outConns);
                outputOutconns.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }
    @Override
    protected void saveInConns(String dirPath) {
        synchronized(nodes){
            int[][][] inConns = new int[nodeCount][][];
            Node t;
            for (int i = 0; i < nodeCount; i++) {
                t = this.nodes.get(i);
                if(t != null){
                    inConns[i] = new int[t.inConns.length][];
                    for (int j = 0; j < t.inConns.length; j++) {
                        inConns[i][j] = t.inConns[j].toArray();
                    }
                }
                else inConns[i] = null;
            }
            Kryo kryo = new Kryo();
            kryo.register(int[].class);
            kryo.register(int[][].class);
            kryo.register(int[][][].class);
            try {
                Output outputInconns = new Output(new FileOutputStream(dirPath + LOCAL_INCONN));
                kryo.writeObject(outputInconns, inConns);
                outputInconns.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    protected void saveInvertLookUp(String dirPath){
        synchronized (nodes){
            int[] invertLookUp = new int[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                invertLookUp[i] = nodes.get(i).item.externalId;
            }
            Kryo kryo = new Kryo();
            kryo.register(int[].class);
            try {
                Output outputInvert = new Output(new FileOutputStream(dirPath + LOCAL_INVERT));
                kryo.writeObject(outputInvert, invertLookUp);
                outputInvert.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }
}
