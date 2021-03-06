package org.kududb.mapred;

/**
 * Created by bimal on 4/13/16.
 */
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.kududb.KuduHandler.HiveKuduWritable;

import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.index.IndexPredicateAnalyzer;
import org.apache.hadoop.hive.ql.index.IndexSearchCondition;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeGenericFuncDesc;
import org.apache.hadoop.hive.ql.plan.TableScanDesc;
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.mapreduce.Job;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Type;
import org.apache.hadoop.mapred.*;
import org.apache.kudu.Schema;
import org.apache.kudu.client.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.net.DNS;

import javax.naming.NamingException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
//import java.time.Instant;
import java.util.*;

/**
 * <p>
 * This input format generates one split per tablet and the only location for each split is that
 * tablet's leader.
 * </p>
 *
 * <p>
 * Hadoop doesn't have the concept of "closing" the input format so in order to release the
 * resources we assume that once either {@link #getSplits(org.apache.hadoop.mapred.JobConf, int)}
 * the object won't be used again and the AsyncKuduClient is shut down.
 * </p>
 */
public class HiveKuduTableInputFormat implements InputFormat<NullWritable, HiveKuduWritable> {

    private static final Log LOG = LogFactory.getLog(HiveKuduTableInputFormat.class);

    private static final long SLEEP_TIME_FOR_RETRIES_MS = 1000;

    /** Job parameter that specifies the input table. */
    static final String INPUT_TABLE_KEY = "kudu.mapreduce.input.table";

    /** Job parameter that specifies if the scanner should cache blocks or not (default: false). */
    static final String SCAN_CACHE_BLOCKS = "kudu.mapreduce.input.scan.cache.blocks";

    /** Job parameter that specifies where the masters are. */
    static final String MASTER_ADDRESSES_KEY = "kudu.mapreduce.master.addresses";

    /** Job parameter that specifies how long we wait for operations to complete (default: 10s). */
    static final String OPERATION_TIMEOUT_MS_KEY = "kudu.mapreduce.operation.timeout.ms";

    /** Job parameter that specifies the address for the name server. */
    static final String NAME_SERVER_KEY = "kudu.mapreduce.name.server";

    /** Job parameter that specifies the encoded column range predicates (may be empty). */
    static final String ENCODED_COLUMN_RANGE_PREDICATES_KEY =
            "kudu.mapreduce.encoded.column.range.predicates";

	static final String HIVE_QUERY_STRING = "hive.query.string";

    /**
     * Job parameter that specifies the column projection as a comma-separated list of column names.
     *
     * Not specifying this at all (i.e. setting to null) or setting to the special string
     * '*' means to project all columns.
     *
     * Specifying the empty string means to project no columns (i.e just count the rows).
     */
    static final String COLUMN_PROJECTION_KEY = "kudu.mapreduce.column.projection";

    /**
     * The reverse DNS lookup cache mapping: address from Kudu => hostname for Hadoop. This cache is
     * used in order to not do DNS lookups multiple times for each tablet server.
     */
    private final Map<String, String> reverseDNSCacheMap = new HashMap<String, String>();

	private static final Object kuduTableMonitor = new Object();

    private Configuration conf;
    private KuduClient client;
    private KuduTable table;
    private long operationTimeoutMs;
    private String nameServer;
    private boolean cacheBlocks;
    private String kuduMasterAddress;

    private KuduClient makeKuduClient() {
    	return new KuduClient.KuduClientBuilder(this.kuduMasterAddress)
                .defaultOperationTimeoutMs(operationTimeoutMs)
                .build();
    }

    private void initParam(JobConf jobConf) {
		if (table == null) {
			String tableName = jobConf.get(INPUT_TABLE_KEY);
			this.kuduMasterAddress = jobConf.get(MASTER_ADDRESSES_KEY);
			this.operationTimeoutMs = jobConf.getLong(OPERATION_TIMEOUT_MS_KEY,
					AsyncKuduClient.DEFAULT_OPERATION_TIMEOUT_MS);
			this.nameServer = jobConf.get(NAME_SERVER_KEY);
			this.cacheBlocks = jobConf.getBoolean(SCAN_CACHE_BLOCKS, false);

			LOG.warn(" the master address here is " + this.kuduMasterAddress);

			this.client = this.makeKuduClient();
			try {
				this.table = client.openTable(tableName);
			} catch (Exception ex) {
				throw new RuntimeException("Could not obtain the table from the master, " +
						"is the master running and is this table created? tablename=" + tableName + " and " +
						"master address= " + this.kuduMasterAddress, ex);
			}
		}
	}

    @Override
    public InputSplit[] getSplits(JobConf jobConf, int i)
            throws IOException {
		synchronized (kuduTableMonitor) {
			return getSplitsInternal(jobConf, i);
		}
	}

	public InputSplit[] getSplitsInternal(JobConf jobConf, int i)
			throws IOException {
		initParam(jobConf);
		if (table == null) {
			throw new IOException("No table was provided");
		}

		String columns = jobConf.get(ColumnProjectionUtils.READ_COLUMN_NAMES_CONF_STR);
		String exprStr = jobConf.get(TableScanDesc.FILTER_EXPR_CONF_STR);

		LOG.warn("input format get filter: " + exprStr);

		if (StringUtils.isBlank(exprStr)) {
			throw new IOException("kudu key condition must be defined");
		}

		ExprNodeGenericFuncDesc filterExpr = Utilities.deserializeExpression(exprStr);
		List<IndexSearchCondition> conditions = new ArrayList<IndexSearchCondition>();
		IndexPredicateAnalyzer analyzer =
				HiveKuduTableInputFormat.newIndexPredicateAnalyzer(table.getSchema().getColumns());
		ExprNodeDesc residualPredicate = analyzer.analyzePredicate(filterExpr, conditions);

		if (residualPredicate != null) {
			LOG.warn("Ignoring residual predicate " + residualPredicate.getExprString());
		}

		List<KuduScanToken> tokens = new KuduPredicateBuilder().toPredicateScan(this.client.newScanTokenBuilder(table),
				table, conditions);

		Path[] tablePaths = FileInputFormat.getInputPaths(jobConf);

		KuduTableSplit[] splits = new KuduTableSplit[tokens.size()];
		for (int j = 0; j < tokens.size(); ++j) {
			splits[j] = new KuduTableSplit(tokens.get(j), tablePaths[0]);
		}
		return splits;
	}

    /**
     * This method might seem alien, but we do this in order to resolve the hostnames the same way
     * Hadoop does. This ensures we get locality if Kudu is running along MR/YARN.
     * @param host hostname we got from the master
     * @param port port we got from the master
     * @return reverse DNS'd address
     */
    private String reverseDNS(String host, Integer port) {
        LOG.warn("I was called : reverseDNS");
        String location = this.reverseDNSCacheMap.get(host);
        if (location != null) {
            return location;
        }
        // The below InetSocketAddress creation does a name resolution.
        InetSocketAddress isa = new InetSocketAddress(host, port);
        if (isa.isUnresolved()) {
            LOG.warn("Failed address resolve for: " + isa);
        }
        InetAddress tabletInetAddress = isa.getAddress();
        try {
            location = domainNamePointerToHostName(
                    DNS.reverseDns(tabletInetAddress, this.nameServer));
            this.reverseDNSCacheMap.put(host, location);
        } catch (NamingException e) {
            LOG.warn("Cannot resolve the host name for " + tabletInetAddress + " because of " + e);
            location = host;
        }
        return location;
    }

	@Override
	public RecordReader<NullWritable, HiveKuduWritable> getRecordReader(InputSplit inputSplit, final JobConf jobConf,
			final Reporter reporter) throws IOException {
		KuduTableSplit tableSplit = (KuduTableSplit) inputSplit;
		LOG.warn("I was called : getRecordReader");
		try {
			initParam(jobConf);
			return new KuduTableRecordReader(tableSplit, this.makeKuduClient(), this.table);
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

//    @Override
    public void setConf(Configuration entries) {
    	LOG.warn("I was called : setConf");
        this.conf = new Configuration(entries);

        String tableName = conf.get(INPUT_TABLE_KEY);
        this.kuduMasterAddress = conf.get(MASTER_ADDRESSES_KEY);
        this.operationTimeoutMs = conf.getLong(OPERATION_TIMEOUT_MS_KEY,
                AsyncKuduClient.DEFAULT_OPERATION_TIMEOUT_MS);
        this.nameServer = conf.get(NAME_SERVER_KEY);
        this.cacheBlocks = conf.getBoolean(SCAN_CACHE_BLOCKS, false);

        LOG.warn(" the master address here is " + this.kuduMasterAddress);

        this.client = this.makeKuduClient();
        try {
            this.table = client.openTable(tableName);
        } catch (Exception ex) {
            throw new RuntimeException("Could not obtain the table from the master, " +
                    "is the master running and is this table created? tablename=" + tableName + " and " +
                    "master address= " + this.kuduMasterAddress, ex);
        }

	}

    /**
     * Given a PTR string generated via reverse DNS lookup, return everything
     * except the trailing period. Example for host.example.com., return
     * host.example.com
     * @param dnPtr a domain name pointer (PTR) string.
     * @return Sanitized hostname with last period stripped off.
     *
     */
    private static String domainNamePointerToHostName(String dnPtr) {
        LOG.warn("I was called : domainNamePointerToHostName");
        if (dnPtr == null)
            return null;
        String r = dnPtr.endsWith(".") ? dnPtr.substring(0, dnPtr.length() - 1) : dnPtr;
        LOG.warn(r);
        return r;
    }

//    @Override
    public Configuration getConf() {
        return conf;
    }

	/**
	 * kudu analyzer
	 * @param columnSchemaList
	 * @return
	 */
	public static IndexPredicateAnalyzer newIndexPredicateAnalyzer(List<ColumnSchema> columnSchemaList) {

		IndexPredicateAnalyzer analyzer = new IndexPredicateAnalyzer();

		ColumnSchema columnSchema;
		for (int i = 0; i < columnSchemaList.size(); i++) {
			columnSchema = columnSchemaList.get(i);
			if (columnSchema.isKey()) {
				analyzer.addComparisonOp(columnSchema.getName(),
						"org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqual",
						"org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqualOrGreaterThan",
						"org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqualOrLessThan",
						"org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPLessThan",
						"org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPGreaterThan");
			}
		}

		return analyzer;
	}

	public static IndexPredicateAnalyzer newIndexPredicateAnalyzer(String[] keyColumns) {

		IndexPredicateAnalyzer analyzer = new IndexPredicateAnalyzer();

		for (int i = 0; i < keyColumns.length; i++) {
			analyzer.addComparisonOp(keyColumns[i],
					"org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqual",
					"org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqualOrGreaterThan",
					"org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqualOrLessThan",
					"org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPLessThan",
					"org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPGreaterThan");
		}

		return analyzer;
	}

}