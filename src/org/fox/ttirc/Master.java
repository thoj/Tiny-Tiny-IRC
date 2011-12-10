package org.fox.ttirc;

import java.io.*;
import java.lang.Thread.*;
import java.nio.channels.*;
import java.sql.*;
import java.util.*;
import java.util.logging.*;
import java.util.prefs.Preferences;

public class Master {
	
	private final String m_version = "0.7.0";
	private final int m_configVersion = 4;
	private final String m_lockFileName = "master.lock";
	
	public static enum DbType { MYSQL, PGSQL };
	
	private final int m_schemaVersion = 6;
	
	private String m_dbKeyParam;
	
	protected Preferences m_prefs;
	protected boolean m_active;
	protected Hashtable<Integer, ConnectionHandler> connections;
	protected int m_idleTimeout = 5*1000;
	
	private String m_lockDir;
	private FileLock m_lock;
	private FileChannel m_lockChannel;
	
	private Connection m_conn;
	private String m_jdbcUrl;
	private String m_dbUser;
	private String m_dbPass;
	private DbType m_dbType;
	
	private Logger logger = Logger.getLogger("org.fox.ttirc");
	private PurgeThread m_purgeThread;
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		Master m = new Master(args);	

		m.Run();				
	}	
	
	public boolean checkSchema() {
		
		try {		
			Statement st = m_conn.createStatement();
			
			st.execute("SELECT schema_version FROM ttirc_version");
			
			ResultSet rs = st.getResultSet();
			
			if (rs.next()) {			
				int testSchemaVersion = rs.getInt(1);
			
				return testSchemaVersion == m_schemaVersion;
			}
		
		} catch (SQLException e) {
			e.printStackTrace();
		}
		
		return false;
	}
	
	public Logger getLogger() {
		return logger;
	}
	
	public String getLockDir() {
		return m_lockDir;
	}
	
	public synchronized Connection getConnection() {
		return m_conn;
	}		
	
	public Connection createConnection() throws SQLException {
		Connection conn = DriverManager.getConnection(m_jdbcUrl, m_dbUser, m_dbPass);
		
		return conn;
	}
	
	private boolean lock() {
		File f = new File(m_lockDir + File.separator + m_lockFileName);
		
		try {		
			m_lockChannel = new RandomAccessFile(f, "rw").getChannel();			
			m_lock = m_lockChannel.tryLock();
			
			if (m_lock != null) {			
				logger.info("Lock acquired successfully (" + f.getName() + ").");
			} else {
				logger.severe("Couldn't acquire the lock: maybe another daemon is already running?");
				return false;
			}
			
		} catch (Exception e) {
			logger.severe("Couldn't make the lock: " + e.toString());
			e.printStackTrace();
			return false;
		}
		
		return true;
	}
	
	public Master(String args[]) {
		this.m_prefs = Preferences.userNodeForPackage(getClass());
		this.m_active = true;
		this.connections = new Hashtable<Integer, ConnectionHandler>(10,10);		

		String prefsNode = null;
		boolean needConfigure = false;
		boolean showHelp = false;
		boolean needCleanup = false;
		String logFileName = null;
		boolean needVersion = false;
	
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			
			if (arg.equals("-help")) showHelp = true;
			if (arg.equals("-node")) prefsNode = args[i+1]; 
			if (arg.equals("-configure")) needConfigure = true;
			if (arg.equals("-cleanup")) needCleanup = true;
			if (arg.equals("-log")) logFileName = args[i+1];
			if (arg.equals("-version")) needVersion = true;
		}
		
		if (needVersion) {
			System.out.println("Tiny Tiny IRC " + getVersion());
			
			int year = Calendar.getInstance().get(Calendar.YEAR);
			
			System.out.println("Copyright (C) 2010-"+year+" Andrew Dolgov <cthulhoo(at)gmail.com>");
			System.exit(0);
		}
		
		if (showHelp) {
			System.out.println("Available options:");
			System.out.println("==================");
			System.out.println(" -help              - Show this help");
			System.out.println(" -node node         - Use custom preferences node");
			System.out.println(" -configure         - Display configuration dialog");
			System.out.println(" -cleanup           - Cleanup data and exit");
			System.out.println(" -log file          - Enable logging to specified file");
			System.out.println(" -version           - Display version information and exit");
			System.exit(0);
		}

		if (logFileName != null) {
			try {
				logger.info("Enabling logging to: " + logFileName);
				Handler fh = new FileHandler(logFileName);
				fh.setFormatter(new SimpleFormatter()); 
				logger.addHandler(fh);
			} catch (IOException e) {
				logger.warning(e.toString());
				e.printStackTrace();
			}
		}

		logger.info("Master " + getVersion() + " starting up...");
		
		if (prefsNode != null) {
			logger.info("Using custom preferences node: " + prefsNode);
			m_prefs = m_prefs.node(prefsNode);
		}
		
		if (m_prefs.getInt("CONFIG_VERSION", -1) != m_configVersion || needConfigure) {
			configure();
		}
		
		String LOCK_DIR = m_prefs.get("LOCK_DIR", "/var/tmp");
		
		File f = new File(LOCK_DIR);
		
		if (!f.isDirectory() || !f.canWrite()) {
			logger.severe("Lock directory [" + LOCK_DIR + "] must be a writable directory." );
			System.exit(2);
		}
		
		this.m_lockDir = LOCK_DIR;
		
		if (!lock()) System.exit(3);
				
		String dbHost = m_prefs.get("DB_HOST", "localhost");
		String dbUser = m_prefs.get("DB_USER", "user");
		String dbPass = m_prefs.get("DB_PASS", "pass");
		String dbName = m_prefs.get("DB_NAME", "user");
		String dbPort = m_prefs.get("DB_PORT", "5432");
		String dbType = m_prefs.get("DB_TYPE", "pgsql");
		
		String jdbcUrl = null;

		if (dbType.equals("mysql")) {
			jdbcUrl = "jdbc:mysql://" + dbHost + ":" + dbPort + 
				"/" + dbName + "?useUnicode=true&characterEncoding=utf8";
			
			m_dbKeyParam = "param";
			m_dbType = DbType.MYSQL;
			
			try {
				Class.forName("com.mysql.jdbc.Driver");
			} catch (ClassNotFoundException e) {
				logger.severe("error: JDBC driver for MySQL not found.");
				e.printStackTrace();
				System.exit(1);			
			}
			
		} else if (dbType.equals("pgsql")) {
			jdbcUrl = "jdbc:postgresql://" + dbHost + ":" + dbPort + 
				"/" + dbName;
			
			m_dbKeyParam = "key";
			m_dbType = DbType.PGSQL;
			
			try {
				Class.forName("org.postgresql.Driver");
			} catch (ClassNotFoundException e) {
				logger.severe("error: JDBC driver for PostgreSQL not found.");
				e.printStackTrace();
				System.exit(1);			
			}

		} else {
			logger.severe("Incorrect database type (expected pgsql or mysql): " + dbType);
			System.exit(10);
		}
		
		this.m_jdbcUrl = jdbcUrl;
		this.m_dbUser = dbUser;
		this.m_dbPass = dbPass;
		
		try {
			logger.info("Establishing database connection...");
			this.m_conn = createConnection();
		} catch (SQLException e) {
			logger.severe("error: Couldn't connect to database.");
			e.printStackTrace();
			System.exit(1);
		}
		
		logger.info("Database connection established."); 

		try {
			cleanup();
			if (needCleanup) System.exit(0);
		} catch (SQLException e) {
			e.printStackTrace();
			System.exit(1);
		}
		
		if (!checkSchema()) {
			logger.severe("error: Incorrect schema version.");
			System.exit(5);
		}
		
		Runtime.getRuntime().addShutdownHook(new Thread() {
		    public void run() { try {
				cleanup();				
				m_purgeThread.kill();
				m_lock.release();
				m_lockChannel.close();
			} catch (Exception e) {
				e.printStackTrace();
			} }
		});

		m_purgeThread = new PurgeThread(this);
		m_purgeThread.start();
	}
	
	public String getVersion() {
		String build = Master.class.getPackage().getImplementationVersion();
		
		if (build != null)		
			return m_version + " (" + build + ")";
		else
			return m_version;
	}

	public void readOption(BufferedReader reader, Preferences prefs, 
			String prefName, String caption, String defaultValue) throws IOException {
		
		String def = prefs.get(prefName, defaultValue);
		
		System.out.print(String.format("%s [%s]: ", caption, def));
		String in = reader.readLine();
		
		if (in.length() == 0) in = def;
		
		if (prefName.equals("LOCK_DIR")) {
			File f = new File(in);		
			in = f.getAbsolutePath();			
		}
		
		prefs.put(prefName, in);
		
	}
	
	public void configure() {
		System.out.println("Tiny Tiny IRC backend configuration");
		System.out.println("===================================");
		
		InputStreamReader input = new InputStreamReader(System.in);
		BufferedReader reader = new BufferedReader(input); 
		boolean configured = false;

		try {
		
			while (!configured) {

				readOption(reader, m_prefs, "LOCK_DIR", "Directory for lock files", "/var/tmp");
				readOption(reader, m_prefs, "DB_TYPE", "Database type (pgsql or mysql)", "pgsql");

				readOption(reader, m_prefs, "DB_HOST", "Database host", "localhost");
				readOption(reader, m_prefs, "DB_PORT", "Database port (usually 5432 or 3306)", "5432");
				readOption(reader, m_prefs, "DB_NAME", "Database name", "ttirc_db");
				readOption(reader, m_prefs, "DB_USER", "Database user", "ttirc_user");
				readOption(reader, m_prefs, "DB_PASS", "Database password", "ttirc_pwd");
				readOption(reader, m_prefs, "PURGE_HOURS", 
						"Purge messages older than this amount of hours", "12");

				System.out.print("Done? [Y/N] ");
				String done = reader.readLine();
				
				configured = done.equalsIgnoreCase("Y");				
			}			
	
			m_prefs.putInt("CONFIG_VERSION", m_configVersion);
			
			System.out.println("Data saved. Please use -configure to change it later.");
			
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void cleanup() throws SQLException {
		
		logger.info("Cleaning up...");

		PreparedStatement ps = getConnection().prepareStatement("UPDATE ttirc_connections SET status = ?" +
				", userhosts = '', active_server = ''");
		
		ps.setInt(1, Constants.CS_DISCONNECTED);
		ps.execute();
		ps.close();
		
		Statement st = getConnection().createStatement();
		
      	st.execute("UPDATE ttirc_channels SET nicklist = ''");

      	st.execute("UPDATE ttirc_system SET value = 'false' WHERE " +
      		m_dbKeyParam + " = 'MASTER_RUNNING'");
	
      	st.close();
	}
	
	public void updateHeartbeat() throws SQLException {
		Statement st = getConnection().createStatement();
		
		st.execute("UPDATE ttirc_system SET value = 'true' WHERE "+m_dbKeyParam+" = 'MASTER_RUNNING'");
		st.execute("UPDATE ttirc_system SET value = NOW() WHERE "+m_dbKeyParam+" = 'MASTER_HEARTBEAT'");
		
		st.close();
	}

	public void purgeOldMessages() throws SQLException {
		int purgeHours = m_prefs.getInt("PURGE_HOURS", 12);
		
		logger.info("Purging old messages (purge_hours = " + purgeHours + ")");
		
		PreparedStatement ps = null;
		
		switch (m_dbType) {
		case PGSQL:
			ps = getConnection().prepareStatement("DELETE FROM ttirc_messages WHERE " +
				"ts < NOW() - CAST(? AS INTERVAL)");
			ps.setString(1, purgeHours + " hours");
			break;
		case MYSQL:
			ps = getConnection().prepareStatement("DELETE FROM ttirc_messages WHERE " +
				"ts < DATE_SUB(NOW(), INTERVAL ? HOUR)");
			ps.setInt(1, purgeHours);
			break;
		}
		
		ps.execute();
		ps.close();
	}
	
	public void checkHandlers() throws SQLException {
		Statement st = getConnection().createStatement();

		Enumeration<Integer> e = connections.keys();
		
		while (e.hasMoreElements()) {
			int connectionId = e.nextElement();	
			
    		ConnectionHandler ch = (ConnectionHandler) connections.get(connectionId);
    		
    		PreparedStatement ps = getConnection().prepareStatement("SELECT id FROM ttirc_connections WHERE id = ? " +
    			"AND enabled = true");
    		
    		ps.setInt(1, connectionId);
    		ps.execute();
    		
    		ResultSet rs = ps.getResultSet();
    		
    		if (!rs.next()) {
    			try {
    				if (ch.getState() != State.TERMINATED) {
    	    			logger.info("Connection " + connectionId + " needs termination.");
    					ch.kill();
    				}
    			} catch (Exception ee) {
    				logger.warning(ee.toString());
    				ee.printStackTrace();
    			}
    		}
    		
    		if (ch.getState() == State.TERMINATED) {
    			logger.info("Connection " + connectionId + " terminated.");
    			connections.remove(connectionId);
    			cleanup(connectionId);
    		}			
		}
		
		String interval = null;
		
		switch (m_dbType) {
		case MYSQL:
			interval = "(heartbeat > DATE_SUB(NOW(), INTERVAL 5 MINUTE) OR ";
			break;
		case PGSQL:
			interval = "(heartbeat > NOW() - INTERVAL '5 minutes' OR ";
			break;
		}
		
	    st.execute("SELECT ttirc_connections.id " +
	    		"FROM ttirc_connections, ttirc_users " +
	    		"WHERE owner_uid = ttirc_users.id AND " +
	    		"visible = true AND " +
	    		interval +
	    		"permanent = true) AND " +
	    		"enabled = true");
	
	    ResultSet rs = st.getResultSet();	    
	    
	    while (rs.next()) {
	    	int connectionId = rs.getInt(1);
	    		    	
	    	if (!connections.containsKey(connectionId)) {
	    	  	logger.info("Spawning connection " + connectionId);
	    	  	
	    	  	ConnectionHandler ch;
	    	  	
	    		ch = new NativeConnectionHandler(connectionId, this);
	    	  	
	    	   	connections.put(connectionId, ch);
	    	   	
	    	   	ch.start();

					// We don't want to flood IRC servers
					
					logger.info("Sleeping to prevent flood...");

					try {
						Thread.sleep(10000);
					} catch (InterruptedException ie) {
						//
					}
	    	}
	    }
	}
	
	public String getNick(int connectionId) throws SQLException {
		PreparedStatement ps = getConnection().prepareStatement("SELECT active_nick FROM " +
				"ttirc_connections WHERE id = ?");
		
		ps.setInt(1, connectionId);
		ps.execute();
		
		ResultSet rs = ps.getResultSet();
		
		if (rs.next()) {
			return rs.getString(1);			
		} else {
			return "?UNKNOWN?";
		}
	}
	
	public void pushMessage(int connectionId, String channel, String message, 
			boolean incoming, int messageType, 
			String fromNick) throws SQLException {
	
		String nick;
		
		if (channel.equals("---")) {
			nick = "---";
		} else {
			nick = getNick(connectionId);
		}
		
		if (fromNick.length() != 0) nick = fromNick;
		
		PreparedStatement ps = getConnection().prepareStatement("INSERT INTO ttirc_messages " +
				"(incoming, connection_id, channel, sender, message, message_type, ts) " +
				" VALUES (?, ?, ?, ?, ?, ?, NOW())");
				
		ps.setBoolean(1, incoming);
		ps.setInt(2, connectionId);
		ps.setString(3, channel);
		ps.setString(4, nick);
		ps.setString(5, message);
		ps.setInt(6, messageType);
		
		ps.execute();
	}
	
	public void cleanup(int connectionId) throws SQLException {
		PreparedStatement ps = getConnection().prepareStatement("UPDATE ttirc_connections " +
				"SET status = ?, active_server = '' " + 
				"WHERE id = ?");
		
		ps.setInt(1, Constants.CS_DISCONNECTED);
		ps.setInt(2, connectionId);
		ps.execute();
		ps.close();
		
		ps = getConnection().prepareStatement("UPDATE ttirc_channels SET nicklist = '', topic = '' " +
				"WHERE connection_id = ?");
		
		ps.setInt(1, connectionId);
		ps.execute();
		ps.close();
		
		pushMessage(connectionId, "---", "DISCONNECT", true, Constants.MSGT_EVENT, "");
	}
	
	public void Run() {
		logger.info("Waiting for clients...");
		
		while (m_active) {
			
			try {				
				if (!checkSchema()) {
					logger.severe("error: Incorrect schema version.");
					System.exit(5);
				}
				
				if (!checkConnection()) {
					logger.severe("error: Database connection lost.");
					System.exit(6);
				}

				updateHeartbeat();				
				checkHandlers();				
			} catch (SQLException e) {
				logger.severe(e.toString());
				System.exit(5);
			}
			
			try {
				Thread.sleep(m_idleTimeout);
			} catch (InterruptedException e) {
				//		
			}
		}
		
		try {
			cleanup();
		} catch (SQLException e) {
			logger.warning(e.toString());
			e.printStackTrace();			
		}
	}
	
	private boolean checkConnection() {
		try {
			Statement st = getConnection().createStatement();
		   	st.execute("SELECT true");
		   	st.close();
		} catch (SQLException e) {
			logger.warning(e.toString());
			e.printStackTrace();
			return false;
		}
		
		return true;
	}

	public void finalize() throws Throwable {
		cleanup();		

		m_purgeThread.kill();
		m_lock.release();
		m_lockChannel.close();
	}

	public class PurgeThread extends Thread {
		Master master;
		private boolean enabled = true;
		
		public PurgeThread(Master master) {
			this.master = master;
		}
		
		public void kill() {
			enabled = false;
		}

		public void run() {
			master.getLogger().info("PurgeThread initialized.");
			
			while (enabled) {
				
				try {
					master.purgeOldMessages();
				} catch (SQLException e) {
					logger.info("PurgeThread exception: " + e.toString());
					e.printStackTrace();
				}
								
				try {
					sleep(1000*600);
				} catch (InterruptedException e) {
					//
				}				
			}
			
			master.getLogger().info("PurgeThread terminated.");
		}
	}

	public DbType getDbType() {
		return m_dbType;
	}
}
