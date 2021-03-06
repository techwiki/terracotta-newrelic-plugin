package com.newrelic.plugins.terracotta.utils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.newrelic.plugins.terracotta.metrics.AbstractMetric;

public class MetricsBufferingWorker {
	private static Logger log = LoggerFactory.getLogger(MetricsBufferingWorker.class);

	private volatile MetricsBuffer metricsBuffer = new MetricsBuffer();
	
	private final String agentName;
	private final MetricsFetcher metricsFetcher;
	private final long intervalInMilliSeconds;
	
	private volatile Long lastExecutedTimeStamp = null;

	private static final long REFRESHINTERVALDEFAULT = 5000L;
	private static final TimeUnit refreshIntervalUnit = TimeUnit.MILLISECONDS;

	private final ScheduledExecutorService cacheTimerService;
	private ScheduledFuture<?> cacheTimerServiceFuture;

	public MetricsBufferingWorker(String agentName, MetricsFetcher metrics) {
		this(agentName, metrics, REFRESHINTERVALDEFAULT);
	}

	public MetricsBufferingWorker(final String agentName, MetricsFetcher metricsFetcher, long intervalInMilliSeconds) {
		super();

		this.agentName = agentName;
		
		if(intervalInMilliSeconds <= 0)
			intervalInMilliSeconds = REFRESHINTERVALDEFAULT;
		this.intervalInMilliSeconds = intervalInMilliSeconds;

		this.metricsFetcher = metricsFetcher;

		//setup the timer thread pool for every 5 seconds
		cacheTimerService = Executors.newScheduledThreadPool(1, new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, String.format("Metrics Agent Worker [%s]", agentName));
			}
		});
	}

	public void startAndMoveOn(){
		//schedule the timer pool to execute a cache search every 5 seconds...which in turn will execute the cache sync operations
		cacheTimerServiceFuture = cacheTimerService.scheduleAtFixedRate(new MetricFetcherOp(), 5L, intervalInMilliSeconds, refreshIntervalUnit);
	}

	public void stop(){
		// clean up and exit
		if(null != cacheTimerServiceFuture)
			cacheTimerServiceFuture.cancel(true);

		shutdownNow(cacheTimerService, 5);
	}

	public void waitForever(){
		if(null != cacheTimerServiceFuture)
			throw new IllegalArgumentException("The Scheduler was not started...make sure to start before calling this method");

		try {
			// getting the future's response will block forever unless an exception is thrown
			cacheTimerServiceFuture.get();	
		} catch (InterruptedException e) {
			System.out.println(String.format("SEVERE: An error has occurred: %s", e.getMessage()));
			e.printStackTrace();
		} catch (CancellationException e) {
			System.out.println(String.format("SEVERE: An error has occurred: %s", e.getMessage()));
			e.printStackTrace();
		} catch (ExecutionException e) {
			// ExecutionException will wrap any java.lang.Error from the polling thread that we should not catch there (e.g. OutOfMemoryError)
			System.out.println(String.format("SEVERE: An error has occurred: %s", e.getMessage()));
			e.printStackTrace();
		} finally {
			// clean up and exit
			log.info("clean up and exit");

			cacheTimerServiceFuture.cancel(true);
			shutdownNow(cacheTimerService);
		}
	}

	@Override
	protected void finalize() throws Throwable {
		super.finalize();
		stop();
	}

	/*
	 * thread executor shutdown
	 */
	private void shutdownNow(ExecutorService pool) {
		shutdownNow(pool, Integer.MAX_VALUE);
	}

	private void shutdownNow(ExecutorService pool, int timeoutInSeconds) {
		pool.shutdown();

		try {
			while(!cacheTimerService.awaitTermination(timeoutInSeconds, TimeUnit.SECONDS));

			pool.shutdownNow(); // Cancel currently executing tasks

			// Wait a while for tasks to respond to being canceled
			if (!pool.awaitTermination(timeoutInSeconds, TimeUnit.SECONDS))
				log.error("Pool did not terminate");
		} catch (InterruptedException ie) {
			// (Re-)Cancel if current thread also interrupted
			pool.shutdownNow();

			// Preserve interrupt status
			Thread.currentThread().interrupt();
		}
	}

	//When calling this method, nothing else can happen on the metricsBuffer object...
	//this use synchronization to make sure that getAll + Reset is done atomically, and no new metrics can be added before this operation is finished.
	public AbstractMetric[] getAndCleanMetrics() {
		AbstractMetric[] metrics = null;
		synchronized (metricsBuffer) {
			metrics = metricsBuffer.getAllMetricsAndReset();
		}
		
		if(null == metrics || metrics.length == 0){
			log.warn(String.format("Buffered metrics are null! Let's try to do a ad-hoc fetch...(If this error continues, investigate further: maybe the buffering threads are not running?)"));
			try{
				MetricsBuffer tempBuffer = new MetricsBuffer();
				getMetricsFetcher().addMetrics(tempBuffer);
				metrics = tempBuffer.getAllMetricsAndReset();
			} catch (Exception exc){
				log.error("Unexpected error...", exc);
			}
		}
		
		return metrics;
	}

	public MetricsFetcher getMetricsFetcher() {
		return metricsFetcher;
	}

	/*
	 * Searches elements in delegated cache, and call refreshOp for every returned results
	 */
	private class MetricFetcherOp implements Runnable {
		public MetricFetcherOp() {
		}

		public void run() {
			try{
				synchronized (metricsBuffer) {
					getMetricsFetcher().addMetrics(metricsBuffer, lastExecutedTimeStamp);
				}
			} catch (Exception exc){
				log.error("Unexpected error...", exc);
			}
			
			lastExecutedTimeStamp = System.currentTimeMillis();
		}
	}

	//not thread safe...
	public static class MetricsBuffer {
		private static Logger log = LoggerFactory.getLogger(MetricsBuffer.class);

		//using a simple hashMap because synchronizing all exposed access on it
		private final Map<String, AbstractMetric> buffer = new HashMap<String, AbstractMetric>();

		public MetricsBuffer() {
			super();
		}

		public String getBufferKey(AbstractMetric metric){
			return (null!=metric)?metric.getNameWithUnit():null;
		}
		
		public AbstractMetric addMetric(AbstractMetric metric, Number... metricValues){
			AbstractMetric existingMetric;
			if((existingMetric = buffer.get(getBufferKey(metric))) == null){
				existingMetric = metric;
				existingMetric.addValue(metricValues);
				buffer.put(getBufferKey(existingMetric), existingMetric);
			} else {
				existingMetric.addValue(metricValues);
			}
			return existingMetric;
		}
		
		public AbstractMetric getBufferedMetric(AbstractMetric metric){
			return buffer.get(getBufferKey(metric));
		}
		
		/* not needed
		 private void addMetric(Metric metric){
			if(null != metric){
				Metric existingMetric;
				if((existingMetric = buffer.get(metric.getName())) == null){
					buffer.put(metric.getName(), new Metric(metric)); //make a copy of the metric object here
				} else {
					existingMetric.add(metric.);
				}
			}
		}

		private void addMetrics(Metric[] metrics){
			if(null != metrics && metrics.length > 0){
				for(Metric newMetric : metrics){
					addMetric(newMetric);
				}
			}
		}*/

		public AbstractMetric[] getAllMetricsAndReset() {
			AbstractMetric[] metrics = null;
			try{
				Set<String> keys = buffer.keySet();
				if(keys.size() > 0){
					metrics = new AbstractMetric[keys.size()];

					//perform a deep copy of all the metrics objects
					int counter = 0;
					for (Iterator<String> iterator = keys.iterator(); iterator.hasNext();counter++) {
						String metricKey = iterator.next();

						try {
							metrics[counter] = buffer.get(metricKey).clone(); //deep copy
						} catch (CloneNotSupportedException e) {
							log.error("An error occurrred during metric cloning", e);
						}
					}
				}
			} finally {
				buffer.clear();
			}
			return metrics;
		}
	}
}
