package thread;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Created by jincong on 16/3/14.
 */
@Service("ThreadPool")
public class ThreadPool {

    private static Logger logger = LoggerFactory.getLogger(ThreadPool.class);


    public static final int DEFAULT_MIN_THREAD_COUNT = 16;

    public static final int DEFAULT_MAX_THREAD_COUNT = 64;

    public static final int DEFAULT_KEEP_ALIVE_TIME = 60;

    public static final int DEFAULT_QUEUE_SIZE = 32;

    public static final long DEFAULT_THREAD_PROCESS_TIME_OUT = 60000L;//60s

    /**
     * 线程池维护线程的最少数量
     */
    private int corePoolSize = DEFAULT_MIN_THREAD_COUNT;

    /**
     * 线程池维护线程的最大数量
     */
    private int maximumPoolSize = DEFAULT_MAX_THREAD_COUNT;

    /**
     * 线程池维护线程所允许的空闲时间
     */
    private int keepAliveTime = DEFAULT_KEEP_ALIVE_TIME;

    /**
     * 线程池所使用的缓冲队列的最大数量,用于创建有界的缓冲队列
     */
    private int queueSize = DEFAULT_QUEUE_SIZE;

    /**
     * 线程池所使用的缓冲队列
     */
    private BlockingQueue<Runnable> workQueue;

    /**
     * 线程池对拒绝任务的处理策略
     */
    private RejectedExecutionHandler handler;

    private ThreadFactory threadFactory;

    private ThreadPoolExecutor threadPoolExecutor;

    private ListeningExecutorService service;

    @PostConstruct
    public void initialize() {
        workQueue = new ArrayBlockingQueue(queueSize);
        threadFactory = new NamedThreadFactory("Parallel-Processor", null, true);
        handler = new ThreadPoolExecutor.CallerRunsPolicy();
        threadPoolExecutor=new ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, TimeUnit.SECONDS, workQueue, threadFactory, handler);
        service= MoreExecutors.listeningDecorator(threadPoolExecutor);
    }

    @PreDestroy
    public void stop() {
        service.shutdownNow();
        workQueue.clear();
    }

    /**
     * 提交并发请求
     *
     * @param taskRequest
     */
    public <V> List<V> submit(final TaskRequest taskRequest) {
        long threadProcessTimeout = DEFAULT_THREAD_PROCESS_TIME_OUT;
        return submit(taskRequest, threadProcessTimeout);
    }

    /**
     * 提交并发请求
     *
     * @param taskRequest
     * @param
     */
    public <V> List<V> submit(final TaskRequest taskRequest, long threadProcessTimeout) {
        if (logger.isInfoEnabled()) {
            logger.info("Try to parallel process Parallel process task count :" + taskRequest.getTaskCount());
        }
        final CountDownLatch latch = new CountDownLatch(taskRequest.getTaskCount());

        List<ListenableFuture<V>> futureList = new ArrayList(taskRequest.getTaskCount());
        for (int i = 0; i < taskRequest.getTaskCount(); i++) {
            final int index = i;
            ListenableFuture<V> futureTaskResult = service.submit(() -> {
                V result = null;
                try {
                    long startTime = System.currentTimeMillis();
                    TaskFunction<V> taskFunction = taskRequest.getTaskList().get(index);
                    result = taskFunction.apply();
                    long endTime = System.currentTimeMillis();
                    if (logger.isInfoEnabled()) {
                        logger.info("Try to parallel process Parallel process task totalTime :" + (endTime - startTime));
                    }
                    return result;
                } catch (Throwable e) {
                    logger.error("thread pool process future task failed:", e);
                    return result;
                } finally {
                    latch.countDown();//计数器减一
                }
            });
            futureList.add(futureTaskResult);
        }
        ListenableFuture<List<V>> successfulFuture=Futures.successfulAsList(futureList);
        if(taskRequest.getCallback()!=null) {
            Futures.addCallback(successfulFuture, taskRequest.getCallback());
        }
        try {
            latch.await(threadProcessTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            logger.error("latch.await parallel process failed, maybe timeout:", e);
        }
        final List taskResultList=new ArrayList<>(taskRequest.getTaskCount());
        try {
            taskResultList.addAll(successfulFuture.get(1L, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            logger.error("thread pool get result exception:", e);
            if (successfulFuture.cancel(true)) {
                logger.warn("task execution time out, thread pool cancel task success!");
            } else {
                logger.error("thread pool cancel task failed!");
            }
        }
        return taskResultList;
    }
}
