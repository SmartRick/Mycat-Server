package io.mycat.buffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.DirectBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DirectByteBuffer池，可以分配任意指定大小的DirectByteBuffer，用完需要归还
 * 堆外直接缓冲池
 * @author wuzhih
 * @author zagnix
 */
@SuppressWarnings("restriction")
public class DirectByteBufferPool implements BufferPool{
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectByteBufferPool.class);
    public static final String LOCAL_BUF_THREAD_PREX = "$_";
    private ByteBufferPage[] allPages;
    private final int chunkSize;
   // private int prevAllocatedPage = 0;
    //private AtomicInteger prevAllocatedPage;
    private AtomicLong prevAllocatedPage;
    private final  int pageSize;
    private final short pageCount;
    private final int conReadBuferChunk ;

     /**
     * 记录对线程ID->该线程的所使用Direct Buffer的size
     */
    private final ConcurrentHashMap<Long,Long> memoryUsage;

    /**
     * 创建堆外缓冲池
     * @param pageSize      缓存页大小
     * @param chunkSize     缓存块大小
     * @param pageCount     缓存页数量
     * @param conReadBuferChunk 读取缓存块大小
     */
    public DirectByteBufferPool(int pageSize, short chunkSize, short pageCount,int conReadBuferChunk) {
        allPages = new ByteBufferPage[pageCount];
        this.chunkSize = chunkSize;
        this.pageSize = pageSize;
        this.pageCount = pageCount;
        this.conReadBuferChunk = conReadBuferChunk;
        //prevAllocatedPage = new AtomicInteger(0);
        prevAllocatedPage = new AtomicLong(0);
        for (int i = 0; i < pageCount; i++) {
            allPages[i] = new ByteBufferPage(ByteBuffer.allocateDirect(pageSize), chunkSize);
        }
        memoryUsage = new ConcurrentHashMap<>();
    }

    public BufferArray allocateArray() {
        return new BufferArray(this);
    }
    /**
     * TODO 当页不够时，考虑扩展内存池的页的数量...........
     * @param buffer
     * @return
     */
    public  ByteBuffer expandBuffer(ByteBuffer buffer){
        int oldCapacity = buffer.capacity();
        int newCapacity = oldCapacity << 1;
        ByteBuffer newBuffer = allocate(newCapacity);
        if(newBuffer != null){
            int newPosition = buffer.position();
            buffer.flip();
            newBuffer.put(buffer);
            newBuffer.position(newPosition);
            recycle(buffer);
            return  newBuffer;
        }
        return null;
    }

    public ByteBuffer allocate(int size) {
       final int theChunkCount = size / chunkSize + (size % chunkSize == 0 ? 0 : 1);
        int selectedPage =  (int)(prevAllocatedPage.incrementAndGet() % allPages.length);
        ByteBuffer byteBuf = allocateBuffer(theChunkCount, 0, selectedPage);
        if (byteBuf == null) {
            byteBuf = allocateBuffer(theChunkCount, selectedPage, allPages.length);
        }
        final long threadId = Thread.currentThread().getId();

        if(byteBuf !=null){

            // 这里必须加锁，因为并发情况下如果allocate和recycle函数操作同一个数据，假设它们都先get到数据，然后allocate先put操作，
            // recycle后进行put操作，这样allocate的put的数据就被覆盖掉
            final ByteBuffer finlBuffer = byteBuf;
         memoryUsage.compute(threadId, (aLong, aLong2) -> {
             if (aLong2 != null){
                 return memoryUsage.get(threadId) + finlBuffer.capacity();
             }else {
                 return (long)finlBuffer.capacity();
             }
         });
        }

        if(byteBuf==null){
            return  ByteBuffer.allocate(size);
        }
        return byteBuf;
    }

    public void recycle(ByteBuffer theBuf) {
    	//堆内buffer直接就清空就好
      	if(theBuf !=null && (!(theBuf instanceof DirectBuffer) )){
    		theBuf.clear();
    		return;
         }

		final long size = theBuf.capacity();

		boolean recycled = false;
        StringBuilder relatedThreadId = new StringBuilder();

		DirectBuffer thisNavBuf = (DirectBuffer) theBuf;//
		int chunkCount = theBuf.capacity() / chunkSize; //chunk的个数
		DirectBuffer parentBuf = (DirectBuffer) thisNavBuf.attachment(); //page的DirectBuffer
		int startChunk = (int) ((thisNavBuf.address() - parentBuf.address()) / chunkSize); //开始chunk的序号
		for (int i = 0; i < allPages.length; i++) { //在所有的页面中查找当前buffer分配的
            if ((recycled = allPages[i].recycleBuffer((ByteBuffer) parentBuf, theBuf, startChunk, chunkCount,
                    relatedThreadId) == true)) {
                break;
            }
        }

        final Long threadId = relatedThreadId.length() > 0 ? Long.parseLong(relatedThreadId.toString())
                : Thread.currentThread().getId();

		memoryUsage.computeIfAbsent(threadId, aLong -> (long)(memoryUsage.get(threadId) - size));

		if (recycled == false) {
			LOGGER.warn("warning ,not recycled buffer " + theBuf);
		}

    }

    private ByteBuffer allocateBuffer(int theChunkCount, int startPage, int endPage) {
        for (int i = startPage; i < endPage; i++) {
            ByteBuffer buffer = allPages[i].allocatChunk(theChunkCount);
            if (buffer != null) {
                prevAllocatedPage.getAndSet(i);
                return buffer;
            }
        }
        return null;
    }

    public int getChunkSize() {
        return chunkSize;
    }

	 @Override
    public ConcurrentHashMap<Long,Long> getNetDirectMemoryUsage() {
        return memoryUsage;
    }

    public int getPageSize() {
        return pageSize;
    }

    public short getPageCount() {
        return pageCount;
    }

    public long capacity() {
	return (long) pageSize * pageCount;
    }

    public long size(){
        return  (long) pageSize * chunkSize * pageCount;
    }

    //TODO
    public  int getSharedOptsCount(){
        return 0;
    }



    public ByteBufferPage[] getAllPages() {
		return allPages;
	}

	public int getConReadBuferChunk() {
        return conReadBuferChunk;
    }

}
