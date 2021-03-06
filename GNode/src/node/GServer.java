package node;

import hdfs.HDFS_Utilities;

import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.RPC;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.data.Stat;

import rpc.GMasterProtocol;
import rpc.GServerProtocol;
import rpc.RpcIOCommons;
import system.CpuUsage;
import system.SystemConf;
import test.Debug;
import zk.Lock;
import zk.ZkIOCommons;
import zk.ZkObtainer;
import data.io.Data_Schema;
import data.io.EdgeData;
import data.io.EdgeInfo;
import data.io.GP_DataType;
import data.io.GraphSchemaCollectionSerializable;
import data.io.Graph_Schema;
import data.io.VertexData;
import data.io.VertexInfo;
import data.io.VertexInfo._EdgeInfo;
import data.writable.BPlusTreeStrLongWritable;
import data.writable.BPlusTreeStrStrWritable;
import data.writable.EdgeCollectionWritable;
import data.writable.StringMapWritable;
import data.writable.StringPairWritable;
import data.writable.VertexCollectionWritable;
import ds.LRULinkedHashMap;
import ds.bplusTree.BPlusTree;

public class GServer extends GNode implements Runnable, GServerProtocol {

	protected Watcher zooWatcher = new Watcher() {
		@Override
		public void process(WatchedEvent event) {
			if (event.getType() == EventType.NodeDataChanged
					&& event.getState() == KeeperState.SyncConnected) {
				org.apache.zookeeper.data.Stat stat;
				try {
					stat = zooKeeper.exists(event.getPath(), null);
					if (stat != null) {
						if (event
								.getPath()
								.contains(
										SystemConf.getInstance().zoo_gp_schema_basePath)) {
							String graphId = event.getPath().substring(
									event.getPath().lastIndexOf('/') + 1);
							GraphSchemaCollectionSerializable gsc = schema_cache
									.get(graphId);
							if (gsc != null) {
								gsc = (GraphSchemaCollectionSerializable) ZkIOCommons
										.unserialize(zooKeeper.getData(
												event.getPath(), zooWatcher,
												stat));
								if (gsc != null) {
									schema_cache.put(graphId, gsc);
								}
							}
						} else if (event
								.getPath()
								.contains(
										SystemConf.getInstance().zoo_ds_schema_basePath)) {
							String dsSchemaId = event.getPath().substring(
									event.getPath().lastIndexOf('/') + 1);
							Data_Schema gsc = dsBufferPool_schema
									.get(dsSchemaId);
							if (gsc != null) {
								gsc = (Data_Schema) ZkIOCommons
										.unserialize(zooKeeper.getData(
												event.getPath(), zooWatcher,
												stat));
								if (gsc != null) {
									dsBufferPool_schema.put(dsSchemaId, gsc);
								}
							}
						}

					}
				} catch (KeeperException | InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

			}
		}
	};

	public GServer(Lock lock, String ip) throws Exception {
		super(lock, ip);
		// init();
	}

	protected void init() throws InterruptedException, KeeperException,
			IOException {
		vBufferPool_w = new LinkedList<VertexInfo>();
		eBufferPool_w = new LinkedList<EdgeInfo>();
		vBufferPool_r = new LRULinkedHashMap<String, VertexInfo>(
				SystemConf.GSERVER_MAX_RBUFFER_VERTEX);
		eBufferPool_r = new LRULinkedHashMap<String, EdgeInfo>(
				SystemConf.GSERVER_MAX_RBUFFER_EDGE);

		vBufferPool_rD = new LRULinkedHashMap<String, VertexData>(
				SystemConf.GSERVER_MAX_RBUFFER_VERTEX);
		eBufferPool_rD = new LRULinkedHashMap<String, EdgeData>(
				SystemConf.GSERVER_MAX_RBUFFER_EDGE);

		vGlobalIndexTree = null;
		eGlobalIndexTree = null;

		vLocalIndexTree = new BPlusTree<String, String>(
				SystemConf.GSERVER_LOCALINDEXTREE_FACTOR);
		eLocalIndexTree = new BPlusTree<String, String>(
				SystemConf.GSERVER_LOCALINDEXTREE_FACTOR);

		hdfs_basePath_vertex = SystemConf.getInstance().hdfs_basePath + "/"
				+ this.ip + "/" + "Vertex";
		HDFS_Utilities.getInstance().CheckPath_All(hdfs_basePath_vertex);
		hdfs_basePath_edge = SystemConf.getInstance().hdfs_basePath + "/"
				+ this.ip + "/" + "Edge";
		HDFS_Utilities.getInstance().CheckPath_All(hdfs_basePath_edge);

		// For DataSetLayer
		HDFS_Utilities.getInstance().CheckPath_All(
				SystemConf.getInstance().hdfs_dsIndex_basePath);
		dsPathIndex = null;
		dsBufferPool_index = new LRULinkedHashMap<>(
				SystemConf.DATASET_INDEX_CACHE_FACTOR);
		dsBufferPool_schema = new LRULinkedHashMap<>(
				SystemConf.DATASET_SCHEMA_CACHE_FACTOR);

		// Read SchemaFile From ZooKeeper
		schema_cache = new LRULinkedHashMap<String, GraphSchemaCollectionSerializable>(
				SystemConf.GSERVER_MAX_GP_SCHEMA_CACHE);
		zooKeeper = new ZkObtainer().getZooKeeper();
		zooKeeper.register(zooWatcher);

		ZkIOCommons.checkPath_All(zooKeeper,
				SystemConf.getInstance().zoo_ds_schema_basePath);
		ZkIOCommons.checkPath_All(zooKeeper,
				SystemConf.getInstance().zoo_gp_schema_basePath);

		// End of Read SchemaFile From Zookeeper

		rpcThread = new Thread(new Runnable() {

			@Override
			public void run() {
				// TODO Auto-generated method stub
				try {
					rpcServer = RPC.getServer(GServer.this,
							SystemConf.getInstance().localIP,
							SystemConf.getInstance().RPC_GSERVER_PORT,
							new Configuration());
					rpcServer.start();
					rpcServer.join();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

			}
		});
		rpcThread.start();

	}

	protected LRULinkedHashMap<String, VertexInfo> vBufferPool_r;
	protected LinkedList<VertexInfo> vBufferPool_w;
	protected LRULinkedHashMap<String, EdgeInfo> eBufferPool_r;
	protected LinkedList<EdgeInfo> eBufferPool_w;

	protected LRULinkedHashMap<String, VertexData> vBufferPool_rD;
	protected LRULinkedHashMap<String, EdgeData> eBufferPool_rD;

	protected BPlusTree<String, String> vGlobalIndexTree;
	protected BPlusTree<String, String> eGlobalIndexTree;

	protected BPlusTree<String, String> vLocalIndexTree;
	protected BPlusTree<String, String> eLocalIndexTree;

	protected LRULinkedHashMap<String, GraphSchemaCollectionSerializable> schema_cache;

	protected int vCount = 0;
	protected int eCount = 0;

	// For DataSetLayer
	protected BPlusTree<String, String> dsPathIndex;
	protected LRULinkedHashMap<String, BPlusTree<String, Long>> dsBufferPool_index;
	protected LRULinkedHashMap<String, Data_Schema> dsBufferPool_schema;

	// For HDFS
	protected String hdfs_basePath_vertex;
	protected String hdfs_basePath_edge;

	// Write
	protected void writeVertex(VertexInfo data) throws IOException {
		if (vBufferPool_w.size() == SystemConf.GSERVER_MAX_WBUFFER_VERTEX) {
			flushToLocal(vBufferPool_w, GP_DataType.Vertex);
		}
		vBufferPool_w.add(data);
		vCount++;
	}

	protected void writeEdge(EdgeInfo data) throws IOException {
		if (eBufferPool_w.size() == SystemConf.GSERVER_MAX_WBUFFER_EDGE) {
			flushToLocal(eBufferPool_w, GP_DataType.Edge);
		}
		eBufferPool_w.add(data);
		eCount++;
	}

	private void flushToLocal(Collection<?> coll, GP_DataType type)
			throws IOException {
		// TODO
		switch (type) {
		case Vertex:
			System.out.println("[" + SystemConf.getTime()
					+ "][gSERVER] Begin to FlushVertex!");
			String retPath_v = HDFS_Utilities.getInstance()
					.flushObjectToHDFS(
							this.hdfs_basePath_vertex,
							new Long(new Date().getTime()).toString(),
							new VertexCollectionWritable(
									(Collection<VertexInfo>) coll));
			for (VertexInfo info : (Collection<VertexInfo>) coll) {
				vLocalIndexTree.insertOrUpdate(info.getId(), retPath_v);
			}
			coll.clear();
			break;
		case Edge:
			System.out.println("[" + SystemConf.getTime()
					+ "][gSERVER] Begin to FlushEdge!");
			String retPath_e = HDFS_Utilities.getInstance().flushObjectToHDFS(
					this.hdfs_basePath_edge,
					new Long(new Date().getTime()).toString(),
					new EdgeCollectionWritable((Collection<EdgeInfo>) coll));
			for (EdgeInfo info : (Collection<EdgeInfo>) coll) {
				eLocalIndexTree.insertOrUpdate(info.getId(), retPath_e);
			}
			coll.clear();
			break;
		default:
			break;
		}
	}

	// End of Write

	// Read
	protected VertexInfo readVertex(String id) throws IOException {
		VertexInfo hitiInfo = null;
		VertexInfo cache = vBufferPool_r.get(id);
		if (cache == null) {
			// TODO Read Write Buffer Pool
			for (VertexInfo info : vBufferPool_w) {
				if (info.getId().equals(id)) {
					hitiInfo = info;
					System.out.println("[" + SystemConf.getTime()
							+ "][gSERVER] Query W Cache Hit!");
					break;
				}
			}
			// TODO Read HDFS File
			if (hitiInfo == null) {
				String filePath = vLocalIndexTree.get(id);
				if (filePath != null) {
					VertexCollectionWritable cw = (VertexCollectionWritable) (HDFS_Utilities
							.getInstance().readFileToObject(filePath));
					LinkedList<VertexInfo> data = (LinkedList<VertexInfo>) cw.coll;
					for (VertexInfo info : data) {
						if (info.getId().equals(id)) {
							hitiInfo = info;
							System.out.println("[" + SystemConf.getTime()
									+ "][gSERVER] HDFS Hit!");
							break;
						}

					}
				}
			}
		} else {
			System.out.println("[" + SystemConf.getTime()
					+ "][gSERVER] Query R Cache Hit!");
			hitiInfo = cache;
		}
		// TODO Update Read Buffer Pool
		if (hitiInfo != null) {
			vBufferPool_r.put(id, hitiInfo);
		}
		return hitiInfo;
	}

	protected VertexData readVertexData(String id) throws IOException {
		VertexData cache = vBufferPool_rD.get(id);
		if (cache == null) {
			VertexInfo info = getVertexInfo_Remote(id);
			if (info == null) {
				System.out.println("no such vertex");
				return null;
			}
			VertexData data = new VertexData();
			System.out.println("readVertexData begin init");
			data.initWithInfo(info);
			data.setSchema(readSchema(info.getGraph_id(), info.getSchema_id()));
			if (data.getSchema()!=null) {
				System.out.println("readVertexData schema read finished");
			}
			
			data.readData(this);
			System.out.println("dataRead finished");
			vBufferPool_rD.put(id, data);
			return data;
		} else {
			return cache;
		}
	}

	protected EdgeInfo readEdge(String id) throws IOException {
		EdgeInfo hitiInfo = null;
		EdgeInfo cache = eBufferPool_r.get(id);
		if (cache == null) {
			// TODO Read Write Buffer Pool
			for (EdgeInfo info : eBufferPool_w) {
				if (info.getId().equals(id))
					hitiInfo = info;
				break;
			}
			// TODO Read HDFS File
			if (hitiInfo == null) {
				String filePath = eLocalIndexTree.get(id);
				if (filePath != null) {
					EdgeCollectionWritable cw = (EdgeCollectionWritable) (HDFS_Utilities
							.getInstance().readFileToObject(filePath));
					LinkedList<EdgeInfo> data = (LinkedList<EdgeInfo>) cw.coll;
					for (EdgeInfo info : data) {
						if (info.getId().equals(id))
							hitiInfo = info;
						break;
					}
				}
			}
		} else {
			hitiInfo = cache;
		}
		// TODO Update Read Buffer Pool
		if (hitiInfo != null) {
			eBufferPool_r.put(id, hitiInfo);
		}
		return hitiInfo;
	}

	protected EdgeData readEdgeData(String id) throws IOException {
		EdgeData cache = eBufferPool_rD.get(id);
		if (cache == null) {
			EdgeInfo info = readEdge(id);
			if (info == null) {
				return null;
			}
			EdgeData data = new EdgeData();
			data.initWithInfo(info);

			VertexInfo vi = readVertex(info.getSource_vertex_id());

			data.setSchema(readSchema(vi.getGraph_id(), info.getSchema_id()));
			data.readData(this);
			eBufferPool_rD.put(id, data);
			return data;
		} else {
			return cache;
		}
	}

	protected boolean VertexExist(String id) {
		if (vBufferPool_r.get(id) == null) {
			if (vLocalIndexTree.get(id) == null) {
				for (VertexInfo info : vBufferPool_w) {
					if (info.getId().equals(id))
						return true;
				}
				return false;
			} else {
				return true;
			}
		} else {
			return true;
		}
	}

	protected boolean EdgeExist(String id) {
		if (eBufferPool_r.get(id) == null) {
			if (eLocalIndexTree.get(id) == null) {
				for (EdgeInfo info : eBufferPool_w) {
					if (info.getId().equals(id))
						return true;
				}
				return false;
			} else {
				return true;
			}
		} else {
			return true;
		}
	}

	protected Graph_Schema readSchema(String graph_id, String schema_id) {
		GraphSchemaCollectionSerializable gsc = schema_cache.get(graph_id);
		if (gsc == null) {
			// will read file from zookeeper
			try {
				org.apache.zookeeper.data.Stat stat = zooKeeper.exists(
						SystemConf.getInstance().zoo_gp_schema_basePath + "/"
								+ graph_id, null);
				if (stat != null) {
					gsc = (GraphSchemaCollectionSerializable) ZkIOCommons
							.unserialize(zooKeeper.getData(
									SystemConf.getInstance().zoo_gp_schema_basePath
											+ "/" + graph_id, zooWatcher, stat));
					if (gsc != null) {
						System.out.println("ZK Read Succeed!");
						schema_cache.put(graph_id, gsc);
						return gsc.schemas.get(schema_id);
					}
				}
			} catch (KeeperException | InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return null;
			}
			return null;
		} else {
			return gsc.schemas.get(schema_id);
		}
	}

	// End of Read

	@Override
	public void run() {
		// TODO Auto-generated method stub
		// TODO Simply output all available gServers
		try {
			init();
		} catch (InterruptedException | KeeperException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		isRunning = true;
		while (isRunning == true) {
			System.out.println("[" + SystemConf.getTime()
					+ "][gSERVER] Running");
			System.out.println("[" + SystemConf.getTime()
					+ "][gSERVER] gServer IP:" + this.ip);
			this.fileLock.checkAndRecover();
			try {
				synchronized (SystemConf.getInstance().isIndexServer) {
					if (SystemConf.getInstance().isIndexServer == true) {
						CpuUsage usage = CpuUsage.getUsage();
						if (usage.cpuUsage > SystemConf.GSERVER_MAXUSAGE_CPU
								|| usage.memUsage > SystemConf.GSERVER_MAXUSAGE_MEM) {
							try {
								GMasterProtocol proxy = RpcIOCommons
										.getMasterProxy();
								proxy.requestToChangeIndexServer(SystemConf
										.getInstance().localIP);
								if (Debug.serverStopProxy)
									RPC.stopProxy(proxy);
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
				}
				Thread.sleep(15000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		// Close Connection
		rpcServer.stop();
	}

	@Override
	public long getProtocolVersion(String arg0, long arg1) throws IOException {
		// TODO Auto-generated method stub
		return SystemConf.RPC_VERSION;
	}

	@Override
	public String storeVertex(VertexInfo vdata, EdgeCollectionWritable edata) {
		// TODO Auto-generated method stub
		System.out.println("[" + SystemConf.getTime()
				+ "][gSERVER] RPC Received to StoreVertex!" + vdata.getId());
		try {
			GMasterProtocol proxy = RpcIOCommons.getMasterProxy();
			writeVertex(vdata);
			if (SystemConf.getInstance().isIndexServer) {
				vGlobalIndexTree.insertOrUpdate(vdata.getId(),
						SystemConf.getInstance().localIP);
			}
			proxy.insertVertexInfoToIndex(vdata.getId(),
					SystemConf.getInstance().localIP);
			for (EdgeInfo info : edata.coll) {
				writeEdge(info);
				if (SystemConf.getInstance().isIndexServer) {
					eGlobalIndexTree.insertOrUpdate(info.getId(),
							SystemConf.getInstance().localIP);
				}
				proxy.insertEdgeInfoToIndex(info.getId(),
						SystemConf.getInstance().localIP);
			}
			if (Debug.serverStopProxy)
				RPC.stopProxy(proxy);
			return "";
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return e.getLocalizedMessage();
		}
	}

	@Override
	public String storeEdge(EdgeInfo edata) {
		System.out.println("[" + SystemConf.getTime()
				+ "][gSERVER] RPC Received to StoreEdge!" + edata.getId());
		try {
			GMasterProtocol proxy = RpcIOCommons.getMasterProxy();

			writeEdge(edata);
			if (SystemConf.getInstance().isIndexServer) {
				eGlobalIndexTree.insertOrUpdate(edata.getId(),
						SystemConf.getInstance().localIP);
			}
			proxy.insertEdgeInfoToIndex(edata.getId(),
					SystemConf.getInstance().localIP);

			if (Debug.serverStopProxy)
				RPC.stopProxy(proxy);

			// Now deal with Vertex
			VertexInfo vi = getVertexInfo(edata.getSource_vertex_id());
			if (vi != null) {
				vi.getEdge_List().add(
						new _EdgeInfo(edata.getId(), edata
								.getTarget_vertex_id()));
				updateVertexInfo(vi);
			}

			return "";
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return e.getLocalizedMessage();
		}
	}

	@Override
	public String storeVertexList(VertexCollectionWritable vdata,
			EdgeCollectionWritable edata) {
		// TODO Auto-generated method stub
		try {
			GMasterProtocol proxy = RpcIOCommons.getMasterProxy();
			for (VertexInfo v : vdata.coll) {
				writeVertex(v);
				if (SystemConf.getInstance().isIndexServer) {
					vGlobalIndexTree.insertOrUpdate(v.getId(),
							SystemConf.getInstance().localIP);
				}
				proxy.insertVertexInfoToIndex(v.getId(),
						SystemConf.getInstance().localIP);
			}
			for (EdgeInfo e : edata.coll) {
				writeEdge(e);
				if (SystemConf.getInstance().isIndexServer) {
					eGlobalIndexTree.insertOrUpdate(e.getId(),
							SystemConf.getInstance().localIP);
				}
				proxy.insertEdgeInfoToIndex(e.getId(),
						SystemConf.getInstance().localIP);
			}
			if (Debug.serverStopProxy)
				RPC.stopProxy(proxy);
			return "";
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return e.getLocalizedMessage();
		}
	}

	@Override
	public VertexInfo getVertexInfo(String id) {
		// TODO Auto-generated method stub
		try {
			if (VertexExist(id) == true) {
				System.out.println("getVertexInfo vertexExist!");
			} else {
				System.out.println("getVertexInfo vertexNonExist!");
			}
			return readVertex(id);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public VertexData getVertexData(String id) {
		// TODO Auto-generated method stub
		try {
			return readVertexData(id);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public EdgeInfo getEdgeInfo(String id) {
		// TODO Auto-generated method stub
		try {
			return readEdge(id);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public EdgeData getEdgeData(String id) {
		// TODO Auto-generated method stub
		try {
			return readEdgeData(id);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public float getMarkForTargetVertex(VertexInfo info) {
		// TODO Auto-generated method stub
		// TODO Should change the Method
		float mark = 0.0f;
		LinkedList<VertexInfo._EdgeInfo> list = info.getEdge_List();
		for (_EdgeInfo e : list) {
			if (VertexExist(e.target_vertex_id))
				mark++;
		}
		mark = mark - (float) (vCount) * 0.01f - (float) (eCount) * 0.01f;
		return mark;
	}

	@Override
	public void stopService() {
		// TODO Auto-generated method stub
		isRunning = false;
	}

	@Override
	public double reportUsageMark() {
		// TODO Auto-generated method stub
		CpuUsage usage;
		try {
			usage = CpuUsage.getUsage();
			if (usage.cpuUsage < SystemConf.GSERVER_MAXUSAGE_CPU
					&& usage.memUsage < SystemConf.GSERVER_MAXUSAGE_MEM) {
				return usage.cpuUsage + usage.memUsage;
			} else {
				return Double.MAX_VALUE;
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return Double.MAX_VALUE;

	}

	@Override
	public void assignIndexServer(BPlusTreeStrStrWritable vertexIndex,
			BPlusTreeStrStrWritable edgeIndex,
			BPlusTreeStrStrWritable dsPathIndex) {
		// TODO Auto-generated method stub
		System.out.println("Index Server Assigned!");
		SystemConf.getInstance().isIndexServer = true;
		vGlobalIndexTree = vertexIndex.getData();
		eGlobalIndexTree = edgeIndex.getData();
		this.dsPathIndex = dsPathIndex.getData();
	}

	@Override
	public void announceIndexServer(String ip) {
		// TODO Auto-generated method stub
		System.out.println("Index Server GET" + ip);
		SystemConf.getInstance().isIndexServer = false;
		SystemConf.getInstance().indexServerIP = ip;
		vGlobalIndexTree = null;
		eGlobalIndexTree = null;
		this.dsPathIndex = null;
	}

	@Override
	public void putVertexInfoToIndex(String vid, String targetIP) {
		// TODO Auto-generated method stub
		vGlobalIndexTree.insertOrUpdate(vid, targetIP);
	}

	@Override
	public void putEdgeInfoToIndex(String eid, String targetIP) {
		// TODO Auto-generated method stub
		eGlobalIndexTree.insertOrUpdate(eid, targetIP);
	}

	@Override
	public void putVListToIndex(StringMapWritable map) {
		// TODO Auto-generated method stub
		for (StringPairWritable spw : map.data) {
			vGlobalIndexTree.insertOrUpdate(spw.key, spw.value);
		}
	}

	@Override
	public void putEListToIndex(StringMapWritable map) {
		// TODO Auto-generated method stub
		for (StringPairWritable spw : map.data) {
			eGlobalIndexTree.insertOrUpdate(spw.key, spw.value);
		}
	}

	@Override
	public void deleteVertexFromIndex(String vid) {
		// TODO Auto-generated method stub
		vGlobalIndexTree.remove(vid);
	}

	@Override
	public void deleteEdgeFromIndex(String eid) {
		// TODO Auto-generated method stub
		eGlobalIndexTree.remove(eid);
	}

	@Override
	public VertexInfo getVertexInfo_Remote(String id) {
		// TODO Auto-generated method stub
		if (SystemConf.getInstance().indexServerIP != null) {
			GServerProtocol proxy;
			try {
				proxy = RpcIOCommons.getGServerProtocol(SystemConf
						.getInstance().indexServerIP);
				String target = proxy.queryVertexToServer(id);
				if (Debug.serverStopProxy)
					RPC.stopProxy(proxy);
				if (target.equals(SystemConf.getInstance().localIP)) {
					return getVertexInfo(id);
				}
				proxy = RpcIOCommons.getGServerProtocol(target);
				VertexInfo info = proxy.getVertexInfo(id);
				if (Debug.serverStopProxy)
					RPC.stopProxy(proxy);
				return info;
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return null;
	}

	@Override
	public String queryVertexToServer(String vid) {
		// TODO Auto-generated method stub
		if (SystemConf.getInstance().isIndexServer == true) {
			return vGlobalIndexTree.get(vid);
		} else {
			return null;
		}
	}

	@Override
	public String queryEdgeToServer(String eid) {
		// TODO Auto-generated method stub
		if (SystemConf.getInstance().isIndexServer == true) {
			return eGlobalIndexTree.get(eid);
		} else {
			return null;
		}
	}

	protected boolean removeEdge_private(String id) {
		if (EdgeExist(id)) {
			if (eBufferPool_r.get(id) != null) {
				eBufferPool_r.remove(id);
			}
			for (EdgeInfo info : eBufferPool_w) {
				if (info.getId().equals(id)) {
					vBufferPool_w.remove(info);
					try {
						GMasterProtocol gm;
						gm = RpcIOCommons.getMasterProxy();
						gm.removeEdgeFromIndex(id);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					return true;
				}
			}
			if (eLocalIndexTree.get(id) != null) {
				String filePath = eLocalIndexTree.get(id);
				if (filePath != null) {
					EdgeCollectionWritable ew;
					try {
						ew = (EdgeCollectionWritable) (HDFS_Utilities
								.getInstance().readFileToObject(filePath));
						LinkedList<EdgeInfo> data = (LinkedList<EdgeInfo>) ew.coll;
						for (EdgeInfo info : data) {
							if (!info.getId().equals(id)) {
								writeEdge(info);
							} else {
							}
						}
						eLocalIndexTree.remove(id);
						HDFS_Utilities.getInstance().deleteFile(filePath);
						GMasterProtocol gm;
						gm = RpcIOCommons.getMasterProxy();
						gm.removeEdgeFromIndex(id);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean removeVertex(String id) {
		if (VertexExist(id)) {
			if (vBufferPool_r.get(id) != null) {
				vBufferPool_r.remove(id);
			}
			for (VertexInfo info : vBufferPool_w) {
				if (info.getId().equals(id)) {
					for (_EdgeInfo ei : info.getEdge_List()) {
						removeEdge_private(ei.id);
					}
					vBufferPool_w.remove(info);
					try {
						GMasterProtocol gm;
						gm = RpcIOCommons.getMasterProxy();
						gm.removeVertexFromIndex(id);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					return true;
				}
			}
			if (vLocalIndexTree.get(id) != null) {
				String filePath = vLocalIndexTree.get(id);
				if (filePath != null) {
					VertexCollectionWritable cw;
					try {
						cw = (VertexCollectionWritable) (HDFS_Utilities
								.getInstance().readFileToObject(filePath));
						LinkedList<VertexInfo> data = (LinkedList<VertexInfo>) cw.coll;
						for (VertexInfo info : data) {
							if (!info.getId().equals(id)) {
								writeVertex(info);
							} else {
								for (_EdgeInfo ei : info.getEdge_List()) {
									// remove all the edges
									removeEdge_private(ei.id);
								}
							}
						}
						vLocalIndexTree.remove(id);
						HDFS_Utilities.getInstance().deleteFile(filePath);
						GMasterProtocol gm;
						gm = RpcIOCommons.getMasterProxy();
						gm.removeVertexFromIndex(id);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean removeEdge(String id, String source_vertex_id) {
		// TODO Auto-generated method stub
		if (EdgeExist(id)) {

			removeEdge_private(id);

			// Now deal with Vertex
			VertexInfo vi = getVertexInfo(source_vertex_id);
			if (vi != null) {
				_EdgeInfo targetEi = null;
				for (_EdgeInfo ei : vi.getEdge_List()) {
					if (ei.id.equals(id)) {
						targetEi = ei;
						break;
					}
				}
				vi.getEdge_List().remove(targetEi);
				updateVertexInfo(vi);
			}

			return true;
		}
		return false;
	}

	@Override
	public boolean removeVertex_Remote(String id) {
		if (SystemConf.getInstance().indexServerIP != null) {
			GServerProtocol proxy;
			try {
				proxy = RpcIOCommons.getGServerProtocol(SystemConf
						.getInstance().indexServerIP);
				String target = proxy.queryVertexToServer(id);
				if (Debug.serverStopProxy)
					RPC.stopProxy(proxy);
				proxy = RpcIOCommons.getGServerProtocol(target);
				boolean result = proxy.removeVertex(id);
				if (Debug.serverStopProxy)
					RPC.stopProxy(proxy);
				return result;
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return false;
	}

	@Override
	public boolean removeEdge_Remote(String id, String source_vertex_id) {
		if (SystemConf.getInstance().indexServerIP != null) {
			GServerProtocol proxy;
			try {
				proxy = RpcIOCommons.getGServerProtocol(SystemConf
						.getInstance().indexServerIP);
				String target = proxy.queryVertexToServer(source_vertex_id);
				if (Debug.serverStopProxy)
					RPC.stopProxy(proxy);
				proxy = RpcIOCommons.getGServerProtocol(target);
				boolean result = proxy.removeEdge(id, source_vertex_id);
				if (Debug.serverStopProxy)
					RPC.stopProxy(proxy);
				return result;
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return false;
	}

	@Override
	public boolean insertOrUpdateSchema(String graph_id, Graph_Schema gs) {
		GraphSchemaCollectionSerializable gsc = schema_cache.get(graph_id);
		if (gsc == null) {
			// will read file from zookeeper
			try {
				org.apache.zookeeper.data.Stat stat = zooKeeper.exists(
						SystemConf.getInstance().zoo_gp_schema_basePath + "/"
								+ graph_id, null);
				if (stat != null) {
					gsc = (GraphSchemaCollectionSerializable) ZkIOCommons
							.unserialize(zooKeeper.getData(
									SystemConf.getInstance().zoo_gp_schema_basePath
											+ "/" + graph_id, zooWatcher, stat));
					if (gsc != null) {
						gsc.schemas.put(gs.getsId(), gs);
						return ZkIOCommons.setSchemaFile(zooKeeper, graph_id,
								gsc);
					}
				} else {
					gsc = new GraphSchemaCollectionSerializable();
					gsc.schemas.put(gs.getsId(), gs);
					return ZkIOCommons.setSchemaFile(zooKeeper, graph_id, gsc);
				}
			} catch (KeeperException | InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return false;
			}
			return false;
		} else {
			gsc.schemas.put(gs.getsId(), gs);
			return ZkIOCommons.setSchemaFile(zooKeeper, graph_id, gsc);
		}
	}

	@Override
	public boolean removeSchema(String graph_id, String schema_id) {
		GraphSchemaCollectionSerializable gsc = schema_cache.get(graph_id);
		if (gsc == null) {
			// will read file from zookeeper
			try {
				org.apache.zookeeper.data.Stat stat = zooKeeper.exists(
						SystemConf.getInstance().zoo_gp_schema_basePath + "/"
								+ graph_id, null);
				if (stat != null) {
					gsc = (GraphSchemaCollectionSerializable) ZkIOCommons
							.unserialize(zooKeeper.getData(
									SystemConf.getInstance().zoo_gp_schema_basePath
											+ "/" + graph_id, zooWatcher, stat));
					if (gsc != null) {
						gsc.schemas.put(schema_id, null);
						return ZkIOCommons.setSchemaFile(zooKeeper, graph_id,
								gsc);
					}
				} else {
					gsc = new GraphSchemaCollectionSerializable();
					gsc.schemas.put(schema_id, null);
					return ZkIOCommons.setSchemaFile(zooKeeper, graph_id, gsc);
				}
			} catch (KeeperException | InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return false;
			}
			return false;
		} else {
			gsc.schemas.put(schema_id, null);
			return ZkIOCommons.setSchemaFile(zooKeeper, graph_id, gsc);
		}
	}

	@Override
	public boolean updateVertexInfo(VertexInfo info) {
		if (VertexExist(info.getId())) {
			vBufferPool_r.put(info.getId(), info);
			for (VertexInfo vi : vBufferPool_w) {
				if (vi.getId().equals(info.getId())) {
					vBufferPool_w.remove(vi);
					vBufferPool_w.add(info);
					return true;
				}
			}
			if (vLocalIndexTree.get(info.getId()) != null) {
				String filePath = vLocalIndexTree.get(info.getId());
				if (filePath != null) {
					VertexCollectionWritable cw;
					try {
						cw = (VertexCollectionWritable) (HDFS_Utilities
								.getInstance().readFileToObject(filePath));
						LinkedList<VertexInfo> data = (LinkedList<VertexInfo>) cw.coll;
						VertexInfo target = null;
						for (VertexInfo vi : data) {
							if (vi.getId().equals(info.getId())) {
								target = vi;
								break;
							}
						}
						data.remove(target);
						data.add(info);

						flushToLocal(data, GP_DataType.Vertex);
						HDFS_Utilities.getInstance().deleteFile(filePath);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean updateVertexInfo_Remote(VertexInfo info) {
		if (SystemConf.getInstance().indexServerIP != null) {
			GServerProtocol proxy;
			try {
				proxy = RpcIOCommons.getGServerProtocol(SystemConf
						.getInstance().indexServerIP);
				String target = proxy.queryVertexToServer(info.getId());
				if (Debug.serverStopProxy)
					RPC.stopProxy(proxy);
				proxy = RpcIOCommons.getGServerProtocol(target);
				boolean result = proxy.updateVertexInfo(info);
				if (Debug.serverStopProxy)
					RPC.stopProxy(proxy);
				return result;
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return false;
	}

	@Override
	public boolean insertDataSet(String dsID, String hdfsPath) {
		if (dsPathIndex.get(dsID) != null) {
			return false;
		} else {
			dsPathIndex.insertOrUpdate(dsID, hdfsPath);
			try {
				GMasterProtocol proxy;
				proxy = RpcIOCommons.getMasterProxy();
				proxy.notifyDataSet_Insert(SystemConf.getInstance().localIP,
						dsID, hdfsPath);
				if (Debug.serverStopProxy)
					RPC.stopProxy(proxy);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				dsPathIndex.remove(dsID);
				e.printStackTrace();
				return false;
			}
			return true;
		}
	}

	@Override
	public boolean removeDataSet(String dsID) {
		if (dsPathIndex.get(dsID) != null) {
			return false;
		} else {
			dsPathIndex.remove(dsID);
			try {
				GMasterProtocol proxy;
				proxy = RpcIOCommons.getMasterProxy();
				proxy.notifyDataSet_Remove(SystemConf.getInstance().localIP,
						dsID);
				if (Debug.serverStopProxy)
					RPC.stopProxy(proxy);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				dsPathIndex.remove(dsID);
				e.printStackTrace();
				return false;
			}
			return true;
		}
	}

	@Override
	public boolean insertDataSet_Sync(String dsID, String hdfsPath) {
		if (dsPathIndex.get(dsID) != null) {
			return false;
		} else {
			dsPathIndex.insertOrUpdate(dsID, hdfsPath);
			return true;
		}
	}

	@Override
	public boolean removeDataSet_Sync(String dsID) {
		if (dsPathIndex.get(dsID) != null) {
			return false;
		} else {
			dsPathIndex.remove(dsID);
			return true;
		}
	}

	@Override
	public String getDataSetPath_Remote(String dsID) {
		if (SystemConf.getInstance().isIndexServer == true) {
			return getDataSetPath(dsID);
		} else {
			if (SystemConf.getInstance().indexServerIP != null) {
				GServerProtocol proxy;
				try {
					proxy = RpcIOCommons.getGServerProtocol(SystemConf
							.getInstance().indexServerIP);
					String result = proxy.getDataSetPath(dsID);
					if (Debug.serverStopProxy)
						RPC.stopProxy(proxy);
					return result;
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			return null;
		}
	}

	@Override
	public String getDataSetPath(String dsID) {
		if (SystemConf.getInstance().isIndexServer == true) {
			return dsPathIndex.get(dsID);
		} else {
			return null;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see rpc.GServerProtocol#createDSIndex(java.lang.String,
	 * java.lang.String, java.lang.String)
	 * 
	 * Attention! Not Completed!
	 */
	@Override
	public String createDSIndex(String dsID, String dschemaID, String attriName) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String updateDSIndex(String dsID, String dschemaID, String attriName) {
		removeDSIndex(dsID, dschemaID, attriName);
		return createDSIndex(dsID, dschemaID, attriName);
	}

	@Override
	public String removeDSIndex(String dsID, String dschemaID, String attriName) {
		dsBufferPool_index.remove(dsID + "@" + dschemaID + "@" + attriName);
		try {
			HDFS_Utilities.getInstance().deleteFile(
					SystemConf.getInstance().hdfs_dsIndex_basePath + "/" + dsID
							+ "@" + dschemaID + "@" + attriName);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			return e.getLocalizedMessage();
		}
		return "";
	}

	@Override
	public void removeDSIndex_Sync(String dsID, String dschemaID,
			String attriName) {
		dsBufferPool_index.remove(dsID + "@" + dschemaID + "@" + attriName);
	}

	@Override
	public BPlusTreeStrLongWritable getDSIndex(String dsID, String dschemaID,
			String attriName) {
		BPlusTree<String, Long> target = dsBufferPool_index.get(dsID + "@"
				+ dschemaID + "@" + attriName);
		if (target != null) {
			return new BPlusTreeStrLongWritable(target);
		} else {
			// Read HDFS file
			BPlusTreeStrLongWritable obj;
			try {
				obj = (BPlusTreeStrLongWritable) HDFS_Utilities.getInstance()
						.readFileToObject(
								SystemConf.getInstance().hdfs_dsIndex_basePath
										+ "/" + dsID + "@" + dschemaID + "@"
										+ attriName);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return null;
			}
			return obj;
		}
	}

	@Override
	public boolean insertOrUpdateDataSchema(String dschemaID, Data_Schema ds) {
		// Update Cache First
		if (dsBufferPool_schema.get(dschemaID) != null) {
			dsBufferPool_schema.put(dschemaID, ds);
		}
		// Update File
		return ZkIOCommons.setDSSchemaFile(zooKeeper, dschemaID, ds);
	}

	@Override
	public boolean removeDataSchema(String dschemaID) {
		// Update Cache First
		dsBufferPool_schema.remove(dschemaID);
		// Update File
		return ZkIOCommons.removeZKFile(zooKeeper,
				SystemConf.getInstance().zoo_ds_schema_basePath + "/"
						+ dschemaID);
	}

	@Override
	public Data_Schema getDataSchema(String dschemaID) {
		// Cache First
		if (dsBufferPool_schema.containsKey(dschemaID)) {
			return dsBufferPool_schema.get(dschemaID);
		} else {
			// File file
			try {
				Stat stat = zooKeeper.exists(
						SystemConf.getInstance().zoo_ds_schema_basePath + "/"
								+ dschemaID, null);
				if (stat != null) {
					Data_Schema gsc = (Data_Schema) ZkIOCommons
							.unserialize(zooKeeper.getData(
									SystemConf.getInstance().zoo_ds_schema_basePath
											+ "/" + dschemaID, zooWatcher, stat));
					dsBufferPool_schema.put(dschemaID, gsc);
					return gsc;
				} else {
					return null;
				}
			} catch (KeeperException | InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return null;
			}
		}
	}

}
