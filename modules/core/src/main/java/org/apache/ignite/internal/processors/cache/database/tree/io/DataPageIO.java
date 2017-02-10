/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.database.tree.io;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.pagemem.PageIdUtils;
import org.apache.ignite.internal.pagemem.PageMemory;
import org.apache.ignite.internal.pagemem.PageUtils;
import org.apache.ignite.internal.processors.cache.CacheObject;
import org.apache.ignite.internal.processors.cache.database.CacheDataRow;
import org.apache.ignite.internal.processors.cache.database.tree.util.PageHandler;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.util.GridUnsafe;
import org.apache.ignite.internal.util.typedef.internal.SB;
import org.jetbrains.annotations.Nullable;
import org.jsr166.LongAdder8;

/**
 * Data pages IO.
 */
public class DataPageIO extends PageIO {
    /** */
    public static final IOVersions<DataPageIO> VERSIONS = new IOVersions<>(
        new DataPageIO(1)
    );

    /** */
    private static final int SHOW_ITEM = 0b0001;

    /** */
    private static final int SHOW_PAYLOAD_LEN = 0b0010;

    /** */
    private static final int SHOW_LINK = 0b0100;

    /** */
    private static final int FREE_LIST_PAGE_ID_OFF = COMMON_HEADER_END;

    /** */
    private static final int FREE_SPACE_OFF = FREE_LIST_PAGE_ID_OFF + 8;

    /** */
    private static final int DIRECT_CNT_OFF = FREE_SPACE_OFF + 2;

    /** */
    private static final int INDIRECT_CNT_OFF = DIRECT_CNT_OFF + 1;

    /** */
    private static final int REMOVED_CNT_OFF = INDIRECT_CNT_OFF + 1;

    /** */
    private static final int REMOVED_ITEMS_OFF = REMOVED_CNT_OFF + 1;

    /** */
    private static final int MAX_REMOVED_CNT = 8;

    /** */
    private static final int REMOVED_ITEM_SIZE = 4;

    /** */
    private static final int FIRST_ENTRY_OFF = REMOVED_ITEMS_OFF + MAX_REMOVED_CNT * REMOVED_ITEM_SIZE;

    /** */
    private static final int ITEMS_OFF = FIRST_ENTRY_OFF + 2;

    /** */
    private static final int ITEM_SIZE = 2;

    /** */
    private static final int PAYLOAD_LEN_SIZE = 2;

    /** */
    private static final int LINK_SIZE = 8;

    /** */
    private static final int FRAGMENTED_FLAG = 0b10000000_00000000;

    /** */
    public static final int MIN_DATA_PAGE_OVERHEAD = ITEMS_OFF + ITEM_SIZE + PAYLOAD_LEN_SIZE + LINK_SIZE;

    /**
     * @param ver Page format version.
     */
    protected DataPageIO(int ver) {
        super(T_DATA, ver);
    }

    /** {@inheritDoc} */
    @Override public void initNewPage(long pageAddr, long pageId, int pageSize) {
        super.initNewPage(pageAddr, pageId, pageSize);

        setEmptyPage(pageAddr, pageSize);
        setFreeListPageId(pageAddr, 0L);
    }

    /**
     * @param pageAddr Page address.
     * @param pageSize Page size.
     */
    private void setEmptyPage(long pageAddr, int pageSize) {
        setDirectCount(pageAddr, 0);
        setIndirectCount(pageAddr, 0);
        setFirstEntryOffset(pageAddr, pageSize, pageSize);
        setRealFreeSpace(pageAddr, pageSize - ITEMS_OFF, pageSize);

        clearRemoved(pageAddr);
    }

    private void clearRemoved(long pageAddr) {
        GridUnsafe.setMemory(pageAddr + REMOVED_CNT_OFF, MAX_REMOVED_CNT * REMOVED_ITEM_SIZE + 1, (byte)0);
    }

    /**
     * @param pageAddr Page address.
     * @param freeListPageId Free list page ID.
     */
    public void setFreeListPageId(long pageAddr, long freeListPageId) {
        PageUtils.putLong(pageAddr, FREE_LIST_PAGE_ID_OFF, freeListPageId);
    }

    /**
     * @param pageAddr Page address.
     * @return Free list page ID.
     */
    public long getFreeListPageId(long pageAddr) {
        return PageUtils.getLong(pageAddr, FREE_LIST_PAGE_ID_OFF);
    }

    /**
     * @param pageAddr Page address.
     * @param dataOff Data offset.
     * @param show What elements of data page entry to show in the result.
     * @return Data page entry size.
     */
    private int getPageEntrySize(long pageAddr, int dataOff, int show) {
        int payloadLen = PageUtils.getShort(pageAddr, dataOff) & 0xFFFF;

        if ((payloadLen & FRAGMENTED_FLAG) != 0)
            payloadLen &= ~FRAGMENTED_FLAG; // We are fragmented and have a link.
        else
            show &= ~SHOW_LINK; // We are not fragmented, never have a link.

        return getPageEntrySize(payloadLen, show);
    }

    /**
     * @param payloadLen Length of the payload, may be a full data row or a row fragment length.
     * @param show What elements of data page entry to show in the result.
     * @return Data page entry size.
     */
    private int getPageEntrySize(int payloadLen, int show) {
        assert payloadLen > 0 : payloadLen;

        int res = payloadLen;

        if ((show & SHOW_LINK) != 0)
            res += LINK_SIZE;

        if ((show & SHOW_ITEM) != 0)
            res += ITEM_SIZE;

        if ((show & SHOW_PAYLOAD_LEN) != 0)
            res += PAYLOAD_LEN_SIZE;

        return res;
    }

    /**
     * @param pageAddr Page address.
     * @param dataOff Entry data offset.
     * @param pageSize Page size.
     */
    private void setFirstEntryOffset(long pageAddr, int dataOff, int pageSize) {
        assert dataOff >= ITEMS_OFF + ITEM_SIZE && dataOff <= pageSize : dataOff;

        PageUtils.putShort(pageAddr, FIRST_ENTRY_OFF, (short)dataOff);
    }

    /**
     * @param pageAddr Page address.
     * @return Entry data offset.
     */
    private int getFirstEntryOffset(long pageAddr) {
        return PageUtils.getShort(pageAddr, FIRST_ENTRY_OFF) & 0xFFFF;
    }

    /**
     * @param pageAddr Page address.
     * @param freeSpace Free space.
     * @param pageSize Page size.
     */
    private void setRealFreeSpace(long pageAddr, int freeSpace, int pageSize) {
        assert freeSpace >= 0 : freeSpace;
        assert freeSpace == actualFreeSpace(pageAddr, pageSize) : freeSpace + " != " + actualFreeSpace(pageAddr, pageSize);

        PageUtils.putShort(pageAddr, FREE_SPACE_OFF, (short)freeSpace);
    }

    /**
     * Free space refers to a "max row size (without any data page specific overhead) which is
     * guaranteed to fit into this data page".
     *
     * @param pageAddr Page address.
     * @return Free space.
     */
    public int getFreeSpace(long pageAddr) {
        if (getFreeItemSlots(pageAddr) == 0)
            return 0;

        int freeSpace = getRealFreeSpace(pageAddr);

        // We reserve size here because of getFreeSpace() method semantics (see method javadoc).
        // It means that we must be able to accommodate a row of size which is equal to getFreeSpace(),
        // plus we will have data page overhead: header of the page as well as item, payload length and
        // possibly a link to the next row fragment.
        freeSpace -= ITEM_SIZE + PAYLOAD_LEN_SIZE + LINK_SIZE;

        return freeSpace < 0 ? 0 : freeSpace;
    }

    /**
     * @param pageAddr Page address.
     * @return {@code true} If there is no useful data in this page.
     */
    public boolean isEmpty(long pageAddr) {
        return getDirectCount(pageAddr) == 0;
    }

    /**
     * Equivalent for {@link #actualFreeSpace(long, int)} but reads saved value.
     *
     * @param pageAddr Page address.
     * @return Free space.
     */
    private int getRealFreeSpace(long pageAddr) {
        return PageUtils.getShort(pageAddr, FREE_SPACE_OFF);
    }

    /**
     * @param pageAddr Page address.
     * @param cnt Direct count.
     */
    private void setDirectCount(long pageAddr, int cnt) {
        assert checkCount(cnt): cnt;

        PageUtils.putByte(pageAddr, DIRECT_CNT_OFF, (byte)cnt);
    }

    /**
     * @param pageAddr Page address.
     * @return Direct count.
     */
    private int getDirectCount(long pageAddr) {
        return PageUtils.getByte(pageAddr, DIRECT_CNT_OFF) & 0xFF;
    }

    private void setRemovedCount(long pageAddr, int cnt) {
        assert cnt >= 0 && cnt <= MAX_REMOVED_CNT : cnt;

        PageUtils.putByte(pageAddr, REMOVED_CNT_OFF, (byte)cnt);
    }

    /**
     * @param pageAddr Page address.
     * @return Direct count.
     */
    private int getRemovedCount(long pageAddr) {
        int cnt = PageUtils.getByte(pageAddr, REMOVED_CNT_OFF) & 0xFF;

        assert cnt >= 0 && cnt <= MAX_REMOVED_CNT : cnt;

        return cnt;
    }

    /**
     * @param pageAddr Page address.
     * @param cnt Indirect count.
     */
    private void setIndirectCount(long pageAddr, int cnt) {
        assert checkCount(cnt): cnt;

        PageUtils.putByte(pageAddr, INDIRECT_CNT_OFF, (byte)cnt);
    }

    /**
     * @param idx Index.
     * @return {@code true} If the index is valid.
     */
    private boolean checkIndex(int idx) {
        return idx >= 0 && idx < 0xFF;
    }

    /**
     * @param cnt Counter value.
     * @return {@code true} If the counter fits 1 byte.
     */
    private boolean checkCount(int cnt) {
        return cnt >= 0 && cnt <= 0xFF;
    }

    /**
     * @param pageAddr Page address.
     * @return Indirect count.
     */
    private int getIndirectCount(long pageAddr) {
        return PageUtils.getByte(pageAddr, INDIRECT_CNT_OFF) & 0xFF;
    }

    /**
     * @param pageAddr Page address.
     * @return Number of free entry slots.
     */
    private int getFreeItemSlots(long pageAddr) {
        return 0xFF - getDirectCount(pageAddr);
    }

    /**
     * @param pageAddr Page address.
     * @param itemId Fixed item ID (the index used for referencing an entry from the outside).
     * @param directCnt Direct items count.
     * @param indirectCnt Indirect items count.
     * @return Found index of indirect item.
     */
    private int findIndirectItemIndex(long pageAddr, int itemId, int directCnt, int indirectCnt) {
        int low = directCnt;
        int high = directCnt + indirectCnt - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;

            int cmp = Integer.compare(itemId(getItem(pageAddr, mid)), itemId);

            if (cmp < 0)
                low = mid + 1;
            else if (cmp > 0)
                high = mid - 1;
            else
                return mid; // found
        }

        throw new IllegalStateException("Item not found: " + itemId);
    }

    /**
     * @param pageAddr Page address.
     * @param pageSize Page size.
     * @return String representation.
     */
    private String printPageLayout(long pageAddr, int pageSize) {
        int directCnt = getDirectCount(pageAddr);
        int indirectCnt = getIndirectCount(pageAddr);
        int free = getRealFreeSpace(pageAddr);

        boolean valid = directCnt >= indirectCnt;

        SB b = new SB();

        b.appendHex(PageIO.getPageId(pageAddr)).a(" [");

        int entriesSize = 0;

        for (int i = 0; i < directCnt; i++) {
            if (i != 0)
                b.a(", ");

            short item = getItem(pageAddr, i);

            if (item < ITEMS_OFF || item >= pageSize)
                valid = false;

            entriesSize += getPageEntrySize(pageAddr, item, SHOW_PAYLOAD_LEN | SHOW_LINK);

            b.a(item);
        }

        b.a("][");

        Collection<Integer> set = new HashSet<>();

        for (int i = directCnt; i < directCnt + indirectCnt; i++) {
            if (i != directCnt)
                b.a(", ");

            short item = getItem(pageAddr, i);

            int itemId = itemId(item);
            int directIdx = directItemIndex(item);

            if (!set.add(directIdx) || !set.add(itemId))
                valid = false;

            assert indirectItem(itemId, directIdx) == item;

            if (itemId < directCnt || directIdx < 0 || directIdx >= directCnt)
                valid = false;

            if (i > directCnt && itemId(getItem(pageAddr, i - 1)) >= itemId)
                valid = false;


            b.a(itemId).a('^').a(directIdx);
        }

        b.a("][free=").a(free);

        int actualFree = pageSize - ITEMS_OFF - (entriesSize + (directCnt + indirectCnt) * ITEM_SIZE);

        if (free != actualFree) {
            b.a(", actualFree=").a(actualFree);

            valid = false;
        }
        else
            b.a("]");

        assert valid : b.toString();

        return b.toString();
    }

    /**
     * @param pageAddr Page address.
     * @param itemId Fixed item ID (the index used for referencing an entry from the outside).
     * @param pageSize Page size.
     * @return Data entry offset in bytes.
     */
    private int getDataOffset(long pageAddr, int itemId, int pageSize) {
        assert checkIndex(itemId): itemId;

        int directCnt = getDirectCount(pageAddr);

        assert directCnt > 0: "itemId=" + itemId + ", directCnt=" + directCnt + ", page=" + printPageLayout(pageAddr, pageSize);

        if (itemId >= directCnt) { // Need to do indirect lookup.
            int indirectCnt = getIndirectCount(pageAddr);

            // Must have indirect items here.
            assert indirectCnt > 0: "itemId=" + itemId + ", directCnt=" + directCnt + ", indirectCnt=" + indirectCnt +
                ", page=" + printPageLayout(pageAddr, pageSize);

            int indirectItemIdx = findIndirectItemIndex(pageAddr, itemId, directCnt, indirectCnt);

            assert indirectItemIdx >= directCnt : indirectItemIdx + " " + directCnt;
            assert indirectItemIdx < directCnt + indirectCnt: indirectItemIdx + " " + directCnt + " " + indirectCnt;

            itemId = directItemIndex(getItem(pageAddr, indirectItemIdx));

            assert itemId >= 0 && itemId < directCnt: itemId + " " + directCnt + " " + indirectCnt; // Direct item.
        }

        return directItemToOffset(getItem(pageAddr, itemId));
    }

    /**
     * @param pageAddr Page address.
     * @param dataOff Points to the entry start.
     * @return Link to the next entry fragment or 0 if no fragments left or if entry is not fragmented.
     */
    private long getNextFragmentLink(long pageAddr, int dataOff) {
        assert isFragmented(pageAddr, dataOff);

        return PageUtils.getLong(pageAddr, dataOff + PAYLOAD_LEN_SIZE);
    }

    /**
     * @param pageAddr Page address.
     * @param dataOff Data offset.
     * @return {@code true} If the data row is fragmented across multiple pages.
     */
    private boolean isFragmented(long pageAddr, int dataOff) {
        return (PageUtils.getShort(pageAddr, dataOff) & FRAGMENTED_FLAG) != 0;
    }

    /**
     * @param pageAddr Page address.
     * @param itemId Item to position on.
     * @param pageSize Page size.
     * @return Size and offset of actual fragment data, and link to the next fragment if data is fragmented.
     */
    public DataPagePayload readPayload(final long pageAddr, final int itemId, final int pageSize) {
        int dataOff = getDataOffset(pageAddr, itemId, pageSize);

        boolean fragmented = isFragmented(pageAddr, dataOff);
        long nextLink = fragmented ? getNextFragmentLink(pageAddr, dataOff) : 0;
        int payloadSize = getPageEntrySize(pageAddr, dataOff, 0);

        return new DataPagePayload(dataOff + PAYLOAD_LEN_SIZE + (fragmented ? LINK_SIZE : 0),
            payloadSize,
            nextLink);
    }

    /**
     * @param pageAddr Page address.
     * @param idx Item index.
     * @return Item.
     */
    private short getItem(long pageAddr, int idx) {
        return PageUtils.getShort(pageAddr, itemOffset(idx));
    }

    private short getItemByOffset(long pageAddr, int itemOff) {
        return PageUtils.getShort(pageAddr, itemOff);
    }

    /**
     * @param pageAddr Page address.
     * @param idx Item index.
     * @param item Item.
     */
    private void setItem(long pageAddr, int idx, short item) {
        PageUtils.putShort(pageAddr, itemOffset(idx), item);
    }

    /**
     * @param idx Index of the item.
     * @return Offset in buffer.
     */
    private int itemOffset(int idx) {
        assert checkIndex(idx): idx;

        return ITEMS_OFF + idx * ITEM_SIZE;
    }

    /**
     * @param directItem Direct item.
     * @return Offset of an entry payload inside of the page.
     */
    private int directItemToOffset(short directItem) {
        return directItem & 0xFFFF;
    }

    /**
     * @param dataOff Data offset.
     * @return Direct item.
     */
    private short directItemFromOffset(int dataOff) {
        assert dataOff >= ITEMS_OFF + ITEM_SIZE && dataOff < Short.MAX_VALUE: dataOff;

        return (short)dataOff;
    }

    /**
     * @param indirectItem Indirect item.
     * @return Index of corresponding direct item.
     */
    private int directItemIndex(short indirectItem) {
        return indirectItem & 0xFF;
    }

    /**
     * @param indirectItem Indirect item.
     * @return Fixed item ID (the index used for referencing an entry from the outside).
     */
    private int itemId(short indirectItem) {
        return (indirectItem & 0xFFFF) >>> 8;
    }

    /**
     * @param itemId Fixed item ID (the index used for referencing an entry from the outside).
     * @param directItemIdx Index of corresponding direct item.
     * @return Indirect item.
     */
    private short indirectItem(int itemId, int directItemIdx) {
        assert checkIndex(itemId): itemId;
        assert checkIndex(directItemIdx): directItemIdx;

        return (short)((itemId << 8) | directItemIdx);
    }

    /**
     * Move the last direct item to the free slot and reference it with indirect item on the same place.
     *
     * @param pageAddr Page address.
     * @param freeDirectIdx Free slot.
     * @param directCnt Direct items count.
     * @param indirectCnt Indirect items count.
     * @return {@code true} If the last direct item already had corresponding indirect item.
     */
    private boolean moveLastItem(long pageAddr, int freeDirectIdx, int directCnt, int indirectCnt) {
        int lastIndirectId = findIndirectIndexForLastDirect(pageAddr, directCnt, indirectCnt);

        int lastItemId = directCnt - 1;

        assert lastItemId != freeDirectIdx;

        short indirectItem = indirectItem(lastItemId, freeDirectIdx);

        assert itemId(indirectItem) == lastItemId && directItemIndex(indirectItem) == freeDirectIdx;

        setItem(pageAddr, freeDirectIdx, getItem(pageAddr, lastItemId));
        setItem(pageAddr, lastItemId, indirectItem);

        assert getItem(pageAddr, lastItemId) == indirectItem;

        if (lastIndirectId != -1) { // Fix pointer to direct item.
            setItem(pageAddr, lastIndirectId, indirectItem(itemId(getItem(pageAddr, lastIndirectId)), freeDirectIdx));

            return true;
        }

        return false;
    }

    /**
     * @param pageAddr Page address.
     * @param directCnt Direct items count.
     * @param indirectCnt Indirect items count.
     * @return Index of indirect item for the last direct item.
     */
    private int findIndirectIndexForLastDirect(long pageAddr, int directCnt, int indirectCnt) {
        int lastDirectId = directCnt - 1;

        for (int i = directCnt, end = directCnt + indirectCnt; i < end; i++) {
            short item = getItem(pageAddr, i);

            if (directItemIndex(item) == lastDirectId)
                return i;
        }

        return -1;
    }

    /**
     * @param pageAddr Page address.
     * @param itemId Item ID.
     * @param pageSize Page size.
     * @param payload Row data.
     * @param row Row.
     * @param rowSize Row size.
     * @return {@code True} if entry is not fragmented.
     * @throws IgniteCheckedException If failed.
     */
    public boolean updateRow(
        final long pageAddr,
        int itemId,
        int pageSize,
        @Nullable byte[] payload,
        @Nullable CacheDataRow row,
        final int rowSize) throws IgniteCheckedException {
        assert checkIndex(itemId) : itemId;
        assert row != null ^ payload != null;

        final int dataOff = getDataOffset(pageAddr, itemId, pageSize);

        if (isFragmented(pageAddr, dataOff))
            return false;

        if (row != null)
            writeRowData(pageAddr, dataOff, rowSize, row, false);
        else
            writeRowData(pageAddr, dataOff, payload);

        return true;
    }

    /**
     * @param pageAddr Page address.
     * @param itemId Fixed item ID (the index used for referencing an entry from the outside).
     * @param pageSize Page size.
     * @return Next link for fragmented entries or {@code 0} if none.
     * @throws IgniteCheckedException If failed.
     */
    public long removeRow(long pageAddr, int itemId, int pageSize) throws IgniteCheckedException {
        assert checkIndex(itemId) : itemId;

        final int dataOff = getDataOffset(pageAddr, itemId, pageSize);
        final long nextLink = isFragmented(pageAddr, dataOff) ? getNextFragmentLink(pageAddr, dataOff) : 0;

        // Record original counts to calculate delta in free space in the end of remove.
        final int directCnt = getDirectCount(pageAddr);
        final int indirectCnt = getIndirectCount(pageAddr);

        int curIndirectCnt = indirectCnt;

        assert directCnt > 0 : directCnt; // Direct count always represents overall number of live items.

        // Remove the last item on the page.
        if (directCnt == 1) {
            assert (indirectCnt == 0 && itemId == 0) ||
                (indirectCnt == 1 && itemId == itemId(getItem(pageAddr, 1))) : itemId;

            setEmptyPage(pageAddr, pageSize);
        }
        else {
            // Get the entry size before the actual remove.
            int rmvEntrySize = getPageEntrySize(pageAddr, dataOff, SHOW_PAYLOAD_LEN | SHOW_LINK);

            if (false && getFirstEntryOffset(pageAddr) == dataOff)
                setFirstEntryOffset(pageAddr, getFirstEntryOffset(pageAddr) + rmvEntrySize, pageSize);
            else {
                int rmvdCnt = getRemovedCount(pageAddr);

                if (rmvdCnt < MAX_REMOVED_CNT) {
                    int item = dataOff << 16 | (rmvEntrySize & 0xFFFF);

                    PageUtils.putInt(pageAddr, REMOVED_ITEMS_OFF + rmvdCnt * REMOVED_ITEM_SIZE, item);

                    setRemovedCount(pageAddr, rmvdCnt + 1);
                }
            }

            int indirectId = 0;

            if (itemId >= directCnt) { // Need to remove indirect item.
                assert indirectCnt > 0;

                indirectId = findIndirectItemIndex(pageAddr, itemId, directCnt, indirectCnt);

                assert indirectId >= directCnt;

                itemId = directItemIndex(getItem(pageAddr, indirectId));

                assert itemId < directCnt;
            }

            boolean dropLast = true;

            if (itemId + 1 < directCnt) // It is not the last direct item.
                dropLast = moveLastItem(pageAddr, itemId, directCnt, indirectCnt);

            if (indirectId == 0) {// For the last direct item with no indirect item.
                if (dropLast)
                    moveItems(pageAddr, directCnt, indirectCnt, -1, pageSize);
                else
                    curIndirectCnt++;
            }
            else {
                if (dropLast)
                    moveItems(pageAddr, directCnt, indirectId - directCnt, -1, pageSize);

                moveItems(pageAddr, indirectId + 1, directCnt + indirectCnt - indirectId - 1, dropLast ? -2 : -1, pageSize);

                if (dropLast)
                    curIndirectCnt--;
            }

            setIndirectCount(pageAddr, curIndirectCnt);
            setDirectCount(pageAddr, directCnt - 1);

            assert getIndirectCount(pageAddr) <= getDirectCount(pageAddr);

            // Increase free space.
            setRealFreeSpace(pageAddr,
                getRealFreeSpace(pageAddr) + rmvEntrySize + ITEM_SIZE * (directCnt - getDirectCount(pageAddr) + indirectCnt - getIndirectCount(pageAddr)),
                pageSize);
        }

        return nextLink;
    }

    /**
     * @param pageAddr Page address.
     * @param idx Index.
     * @param cnt Count.
     * @param step Step.
     * @param pageSize Page size.
     */
    private void moveItems(long pageAddr, int idx, int cnt, int step, int pageSize) {
        assert cnt >= 0: cnt;

        if (cnt != 0)
            moveBytes(pageAddr, itemOffset(idx), cnt * ITEM_SIZE, step * ITEM_SIZE, pageSize);
    }

    /**
     * @param newEntryFullSize New entry full size (with item, length and link).
     * @param firstEntryOff First entry data offset.
     * @param directCnt Direct items count.
     * @param indirectCnt Indirect items count.
     * @return {@code true} If there is enough space for the entry.
     */
    private boolean isEnoughSpace(int newEntryFullSize, int firstEntryOff, int directCnt, int indirectCnt) {
        return ITEMS_OFF + ITEM_SIZE * (directCnt + indirectCnt) <= firstEntryOff - newEntryFullSize;
    }

    /**
     * Adds row to this data page and sets respective link to the given row object.
     *
     * @param pageAddr Page address.
     * @param row Cache data row.
     * @param rowSize Row size.
     * @param pageSize Page size.
     * @throws IgniteCheckedException If failed.
     */
    public void addRow(
        final long pageAddr,
        CacheDataRow row,
        final int rowSize,
        final int pageSize
    ) throws IgniteCheckedException {
        assert rowSize <= getFreeSpace(pageAddr): "can't call addRow if not enough space for the whole row";
        assert row.link() == 0;

        int fullEntrySize = getPageEntrySize(rowSize, SHOW_PAYLOAD_LEN | SHOW_ITEM);

        int directCnt = getDirectCount(pageAddr);
        int indirectCnt = getIndirectCount(pageAddr);

        int dataOff = getDataOffsetForWrite(pageAddr, fullEntrySize, directCnt, indirectCnt, pageSize, row);

        if (row.link() == 0) {
            writeRowData(pageAddr, dataOff, rowSize, row, true);

            int itemId = addItem(pageAddr, fullEntrySize, directCnt, indirectCnt, dataOff, pageSize);

            setLink(row, pageAddr, itemId);
        }
    }

    /**
     * Adds row to this data page and sets respective link to the given row object.
     *
     * @param pageAddr Page address.
     * @param payload Payload.
     * @param pageSize Page size.
     * @throws IgniteCheckedException If failed.
     */
    public void addRow(
        long pageAddr,
        byte[] payload,
        int pageSize
    ) throws IgniteCheckedException {
        assert payload.length <= getFreeSpace(pageAddr): "can't call addRow if not enough space for the whole row";

        int fullEntrySize = getPageEntrySize(payload.length, SHOW_PAYLOAD_LEN | SHOW_ITEM);

        int directCnt = getDirectCount(pageAddr);
        int indirectCnt = getIndirectCount(pageAddr);

        int dataOff = getDataOffsetForWrite(pageAddr, fullEntrySize, directCnt, indirectCnt, pageSize, null);

        writeRowData(pageAddr, dataOff, payload);

        addItem(pageAddr, fullEntrySize, directCnt, indirectCnt, dataOff, pageSize);
    }

    /**
     * @param pageAddr Page address.
     * @param entryFullSize New entry full size (with item, length and link).
     * @param directCnt Direct items count.
     * @param indirectCnt Indirect items count.
     * @param dataOff First entry offset.
     * @param pageSize Page size.
     * @return First entry offset after compaction.
     */
    private int compactIfNeed(
        final long pageAddr,
        final int entryFullSize,
        final int directCnt,
        final int indirectCnt,
        int dataOff,
        int pageSize,
        CacheDataRow row
    ) throws IgniteCheckedException {
        if (!isEnoughSpace(entryFullSize, dataOff, directCnt, indirectCnt)) {
            dataOff = compactDataEntries(pageAddr, directCnt, indirectCnt, pageSize, entryFullSize, row, dataOff);

            assert dataOff == 0 || isEnoughSpace(entryFullSize, dataOff, directCnt, indirectCnt);
        }

        return dataOff;
    }

    /**
     * Put item reference on entry.
     *
     * @param pageAddr Page address.
     * @param fullEntrySize Full entry size (with link, payload size and item).
     * @param directCnt Direct items count.
     * @param indirectCnt Indirect items count.
     * @param dataOff Data offset.
     * @param pageSize Page size.
     * @return Item ID.
     */
    private int addItem(final long pageAddr,
        final int fullEntrySize,
        final int directCnt,
        final int indirectCnt,
        final int dataOff,
        final int pageSize)
    {
        setFirstEntryOffset(pageAddr, dataOff, pageSize);

        int itemId = insertItem(pageAddr, dataOff, directCnt, indirectCnt, pageSize);

        assert checkIndex(itemId): itemId;
        assert getIndirectCount(pageAddr) <= getDirectCount(pageAddr);

        // Update free space. If number of indirect items changed, then we were able to reuse an item slot.
        setRealFreeSpace(pageAddr,
            getRealFreeSpace(pageAddr) - fullEntrySize + (getIndirectCount(pageAddr) != indirectCnt ? ITEM_SIZE : 0),
            pageSize);

        return itemId;
    }

    /**
     * @param pageAddr Page address.
     * @param fullEntrySize Full entry size.
     * @param directCnt Direct items count.
     * @param indirectCnt Indirect items count.
     * @param pageSize Page size.
     * @return Offset in the buffer where the entry must be written.
     */
    private int getDataOffsetForWrite(long pageAddr,
        int fullEntrySize,
        int directCnt,
        int indirectCnt,
        int pageSize,
        CacheDataRow row) throws IgniteCheckedException {
        int dataOff = getFirstEntryOffset(pageAddr);

        // Compact if we do not have enough space for entry.
        dataOff = compactIfNeed(pageAddr, fullEntrySize, directCnt, indirectCnt, dataOff, pageSize, row);

        // We will write data right before the first entry.
        dataOff -= fullEntrySize - ITEM_SIZE;

        return dataOff;
    }

    /**
     * Adds maximum possible fragment of the given row to this data page and sets respective link to the row.
     *
     * @param pageMem Page memory.
     * @param pageAddr Page address.
     * @param row Cache data row.
     * @param written Number of bytes of row size that was already written.
     * @param rowSize Row size.
     * @param pageSize Page size.
     * @return Written payload size.
     * @throws IgniteCheckedException If failed.
     */
    public int addRowFragment(
        PageMemory pageMem,
        long pageAddr,
        CacheDataRow row,
        int written,
        int rowSize,
        int pageSize
    ) throws IgniteCheckedException {
        return addRowFragment(pageMem, pageAddr, written, rowSize, row.link(), row, null, pageSize);
    }

    /**
     * Adds this payload as a fragment to this data page.
     *
     * @param pageAddr Page address.
     * @param payload Payload bytes.
     * @param lastLink Link to the previous written fragment (link to the tail).
     * @param pageSize Page size.
     * @throws IgniteCheckedException If failed.
     */
    public void addRowFragment(
        long pageAddr,
        byte[] payload,
        long lastLink,
        int pageSize
    ) throws IgniteCheckedException {
        addRowFragment(null, pageAddr, 0, 0, lastLink, null, payload, pageSize);
    }

    /**
     * Adds maximum possible fragment of the given row to this data page and sets respective link to the row.
     *
     * @param pageMem Page memory.
     * @param pageAddr Page address.
     * @param written Number of bytes of row size that was already written.
     * @param rowSize Row size.
     * @param lastLink Link to the previous written fragment (link to the tail).
     * @param row Row.
     * @param payload Payload bytes.
     * @param pageSize Page size.
     * @return Written payload size.
     * @throws IgniteCheckedException If failed.
     */
    private int addRowFragment(
        PageMemory pageMem,
        long pageAddr,
        int written,
        int rowSize,
        long lastLink,
        CacheDataRow row,
        byte[] payload,
        int pageSize
    ) throws IgniteCheckedException {
        assert payload == null ^ row == null;

        int directCnt = getDirectCount(pageAddr);
        int indirectCnt = getIndirectCount(pageAddr);

        int payloadSize = payload != null ? payload.length :
            Math.min(rowSize - written, getFreeSpace(pageAddr));

        int fullEntrySize = getPageEntrySize(payloadSize, SHOW_PAYLOAD_LEN | SHOW_LINK | SHOW_ITEM);
        int dataOff = getDataOffsetForWrite(pageAddr, fullEntrySize, directCnt, indirectCnt, pageSize, null);

        if (payload == null) {
            ByteBuffer buf = pageMem.pageBuffer(pageAddr);

            buf.position(dataOff);

            short p = (short)(payloadSize | FRAGMENTED_FLAG);

            buf.putShort(p);
            buf.putLong(lastLink);

            int rowOff = rowSize - written - payloadSize;

            writeFragmentData(row, buf, rowOff, payloadSize);
        }
        else {
            PageUtils.putShort(pageAddr, dataOff, (short)(payloadSize | FRAGMENTED_FLAG));

            PageUtils.putLong(pageAddr, dataOff + 2, lastLink);

            PageUtils.putBytes(pageAddr, dataOff + 10, payload);
        }

        int itemId = addItem(pageAddr, fullEntrySize, directCnt, indirectCnt, dataOff, pageSize);

        if (row != null)
            setLink(row, pageAddr, itemId);

        return payloadSize;
    }

    /**
     * @param row Row to set link to.
     * @param pageAddr Page address.
     * @param itemId Item ID.
     */
    private void setLink(CacheDataRow row, long pageAddr, int itemId) {
        row.link(PageIdUtils.link(getPageId(pageAddr), itemId));
    }

    /**
     * Write row data fragment.
     *
     * @param row Row.
     * @param buf Byte buffer.
     * @param rowOff Offset in row data bytes.
     * @param payloadSize Data length that should be written in a fragment.
     * @throws IgniteCheckedException If failed.
     */
    private void writeFragmentData(
        final CacheDataRow row,
        final ByteBuffer buf,
        final int rowOff,
        final int payloadSize
    ) throws IgniteCheckedException {
        final int keySize = row.key().valueBytesLength(null);
        final int valSize = row.value().valueBytesLength(null);

        int written = writeFragment(row, buf, rowOff, payloadSize, EntryPart.KEY, keySize, valSize);
        written += writeFragment(row, buf, rowOff + written, payloadSize - written, EntryPart.EXPIRE_TIME, keySize, valSize);
        written += writeFragment(row, buf, rowOff + written, payloadSize - written, EntryPart.VALUE, keySize, valSize);
        written += writeFragment(row, buf, rowOff + written, payloadSize - written, EntryPart.VERSION, keySize, valSize);

        assert written == payloadSize;
    }

    /**
     * Try to write fragment data.
     *
     * @param rowOff Offset in row data bytes.
     * @param payloadSize Data length that should be written in this fragment.
     * @param type Type of the part of entry.
     * @return Actually written data.
     * @throws IgniteCheckedException If fail.
     */
    private int writeFragment(
        final CacheDataRow row,
        final ByteBuffer buf,
        final int rowOff,
        final int payloadSize,
        final EntryPart type,
        final int keySize,
        final int valSize
    ) throws IgniteCheckedException {
        if (payloadSize == 0)
            return 0;

        final int prevLen;
        final int curLen;

        switch (type) {
            case KEY:
                prevLen = 0;
                curLen = keySize;

                break;

            case EXPIRE_TIME:
                prevLen = keySize;
                curLen = keySize + 8;

                break;

            case VALUE:
                prevLen = keySize + 8;
                curLen = keySize + valSize + 8;

                break;

            case VERSION:
                prevLen = keySize + valSize + 8;
                curLen = keySize + valSize + CacheVersionIO.size(row.version(), false) + 8;

                break;

            default:
                throw new IllegalArgumentException("Unknown entry part type: " + type);
        }

        if (curLen <= rowOff)
            return 0;

        final int len = Math.min(curLen - rowOff, payloadSize);

        if (type == EntryPart.EXPIRE_TIME)
            writeExpireTimeFragment(buf, row.expireTime(), rowOff, len, prevLen);
        else if (type != EntryPart.VERSION) {
            // Write key or value.
            final CacheObject co = type == EntryPart.KEY ? row.key() : row.value();

            co.putValue(buf, rowOff - prevLen, len);
        }
        else
            writeVersionFragment(buf, row.version(), rowOff, len, prevLen);

        return len;
    }

    /**
     * @param buf Byte buffer.
     * @param ver Version.
     * @param rowOff Row offset.
     * @param len Length.
     * @param prevLen previous length.
     */
    private void writeVersionFragment(ByteBuffer buf, GridCacheVersion ver, int rowOff, int len, int prevLen) {
        int verSize = CacheVersionIO.size(ver, false);

        assert len <= verSize: len;

        if (verSize == len) { // Here we check for equality but not <= because version is the last.
            // Here we can write version directly.
            CacheVersionIO.write(buf, ver, false);
        }
        else {
            // We are in the middle of cache version.
            ByteBuffer verBuf = ByteBuffer.allocate(verSize);

            verBuf.order(buf.order());

            CacheVersionIO.write(verBuf, ver, false);

            buf.put(verBuf.array(), rowOff - prevLen, len);
        }
    }

    /**
     * @param buf Byte buffer.
     * @param expireTime Expire time.
     * @param rowOff Row offset.
     * @param len Length.
     * @param prevLen previous length.
     */
    private void writeExpireTimeFragment(ByteBuffer buf, long expireTime, int rowOff, int len, int prevLen) {
        int size = 8;

        if (size <= len)
            buf.putLong(expireTime);
        else {
            ByteBuffer timeBuf = ByteBuffer.allocate(size);

            timeBuf.order(buf.order());

            timeBuf.putLong(expireTime);

            buf.put(timeBuf.array(), rowOff - prevLen, len);
        }
    }

    /**
     *
     */
    private enum EntryPart {
        /** */
        KEY,

        /** */
        VALUE,

        /** */
        VERSION,

        /** */
        EXPIRE_TIME
    }

    /**
     * @param pageAddr Page address.
     * @param dataOff Data offset.
     * @param directCnt Direct items count.
     * @param indirectCnt Indirect items count.
     * @param pageSize Page size.
     * @return Item ID (insertion index).
     */
    private int insertItem(long pageAddr, int dataOff, int directCnt, int indirectCnt, int pageSize) {
        if (indirectCnt > 0) {
            // If the first indirect item is on correct place to become the last direct item, do the transition
            // and insert the new item into the free slot which was referenced by this first indirect item.
            short item = getItem(pageAddr, directCnt);

            if (itemId(item) == directCnt) {
                int directItemIdx = directItemIndex(item);

                setItem(pageAddr, directCnt, getItem(pageAddr, directItemIdx));
                setItem(pageAddr, directItemIdx, directItemFromOffset(dataOff));

                setDirectCount(pageAddr, directCnt + 1);
                setIndirectCount(pageAddr, indirectCnt - 1);

                return directItemIdx;
            }
        }

        // Move all the indirect items forward to make a free slot and insert new item at the end of direct items.
        moveItems(pageAddr, directCnt, indirectCnt, +1, pageSize);

        setItem(pageAddr, directCnt, directItemFromOffset(dataOff));

        setDirectCount(pageAddr, directCnt + 1);
        assert getDirectCount(pageAddr) == directCnt + 1;

        return directCnt; // Previous directCnt will be our itemId.
    }

    private boolean canAddItem(long pageAddr, int directCnt, int indirectCnt, int firstEntryOff) {
        if (indirectCnt > 0) {
            short item = getItemByOffset(pageAddr, ITEMS_OFF + directCnt * ITEM_SIZE);

            if (itemId(item) == directCnt)
                return true;
        }

        return firstEntryOff > (directCnt + indirectCnt) * ITEM_SIZE + ITEMS_OFF + ITEM_SIZE;
    }

    public final static LongAdder8 cnt = new LongAdder8();
    public final static LongAdder8 foundCnt1 = new LongAdder8();
    public final static LongAdder8 foundCnt2 = new LongAdder8();

    /**
     * @param pageAddr Page address.
     * @param directCnt Direct items count.
     * @param pageSize Page size.
     * @return New first entry offset.
     */
    private int compactDataEntries(long pageAddr,
        int directCnt,
        int indirectCnt,
        int pageSize,
        int newEntrySize,
        CacheDataRow row,
        int firstEntryOff) throws IgniteCheckedException {
        cnt.increment();

        assert checkCount(directCnt): directCnt;

        int rmvdCnt = getRemovedCount(pageAddr);

        boolean canAddItem;

        if (rmvdCnt > 0 && row != null) {
            canAddItem = canAddItem(pageAddr, directCnt, indirectCnt, firstEntryOff);

            if (canAddItem) {
                int cnt = 0;

                int rmvdItemOff = REMOVED_ITEMS_OFF;

                int diff = Integer.MAX_VALUE;
                int foundDataOff = 0;
                int foundRmvItemOff = 0;

                for (int i = 0; i < MAX_REMOVED_CNT; i++) {
                    int rmvdItem = PageUtils.getInt(pageAddr, rmvdItemOff);

                    if (rmvdItem != 0) {
                        int size = rmvdItem & 0xFFFF;

                        assert size >= 0 && size < pageSize : size;

                        int diff0 = size - newEntrySize;

                        if (diff0 > 0) {
                            if (diff0 < diff) {
                                foundDataOff = rmvdItem >>> 16;
                                diff = diff0;
                                foundRmvItemOff = rmvdItemOff;
                            }
                        } else if (diff0 == 0) {
                            foundRmvItemOff = rmvdItemOff;
                            foundDataOff = rmvdItem >>> 16;

                            break;
                        }

                        if (++cnt == rmvdCnt)
                            break;
                    }

                    rmvdItemOff += REMOVED_ITEM_SIZE;
                }

                if (foundDataOff != 0) {
                    foundCnt1.increment();

                    try {
                        assert foundDataOff > ITEMS_OFF && foundDataOff < pageSize /*&& newEntryOff != getFirstEntryOffset(pageAddr)*/ :
                            "new=" + foundDataOff +
                                ", first=" + getFirstEntryOffset(pageAddr) +
                                ", newSize=" + newEntrySize +
                                ", free=" + actualFreeSpace(pageAddr, pageSize) +
                                ", page=" + printPageLayout(pageAddr, pageSize);

                        writeRowData(pageAddr, foundDataOff, newEntrySize - ITEM_SIZE - PAYLOAD_LEN_SIZE, row, true);

                        int itemId = insertItem(pageAddr, foundDataOff, directCnt, indirectCnt, pageSize);

                        assert checkIndex(itemId) : itemId;
                        assert getIndirectCount(pageAddr) <= getDirectCount(pageAddr);

                        // Update free space. If number of indirect items changed, then we were able to reuse an item slot.
                        setRealFreeSpace(pageAddr,
                            getRealFreeSpace(pageAddr) - newEntrySize + (getIndirectCount(pageAddr) != indirectCnt ? ITEM_SIZE : 0),
                            pageSize);

                        setLink(row, pageAddr, itemId);

                        PageUtils.putInt(pageAddr, foundRmvItemOff, 0);
                        setRemovedCount(pageAddr, rmvdCnt - 1);
                    }
                    catch (AssertionError e) {
                        e.printStackTrace(System.out);

                        throw e;
                    }

                    return 0;
                }
            }
            else
                row = null;
        }

        int[] offs = new int[directCnt];

        int itemOff = ITEMS_OFF;

        for (int i = 0; i < directCnt; i++) {
            int off = directItemToOffset(getItemByOffset(pageAddr, itemOff));

            offs[i] = (off << 8) | i; // This way we'll be able to sort by offset using Arrays.sort(...).

            itemOff += ITEM_SIZE;
        }

        Arrays.sort(offs);

        if (row != null && rmvdCnt == 0) {
//            boolean canAddItem = false;
//
//            if (indirectCnt > 0) {
//                short item = getItemByOffset(pageAddr, itemOff);
//
//                if (itemId(item) == directCnt)
//                    canAddItem = true;
//            }
//
//            if (!canAddItem) {
//                int firstOff = offs[0] >>> 8;
//                canAddItem = firstOff > (directCnt + indirectCnt) * ITEM_SIZE + ITEMS_OFF + ITEM_SIZE;
//            }
            canAddItem = canAddItem(pageAddr, directCnt, indirectCnt, firstEntryOff);

            if (!canAddItem)
                row = null;
        }


//        {
//            if (row != null) {
//                int off = offs[0] >>> 8;
//                int size = getPageEntrySize(pageAddr, off, SHOW_PAYLOAD_LEN | SHOW_LINK);
//
//                for (int i = 1; i < directCnt; i++) {
//                    int off1 = offs[i] >>> 8;
//
//                    int end = (off + size);
//
//                    int free = off1 - end;
//
//                    if (free >= newEntrySize) {
//                        int newEntryOff = end;
//
//                        writeRowData(pageAddr, newEntryOff, newEntrySize - ITEM_SIZE - PAYLOAD_LEN_SIZE, row, true);
//
//                        int itemId = insertItem(pageAddr, newEntryOff, directCnt, indirectCnt, pageSize);
//
//                        assert checkIndex(itemId): itemId;
//                        assert getIndirectCount(pageAddr) <= getDirectCount(pageAddr);
//
//                        // Update free space. If number of indirect items changed, then we were able to reuse an item slot.
//                        setRealFreeSpace(pageAddr,
//                            getRealFreeSpace(pageAddr) - newEntrySize + (getIndirectCount(pageAddr) != indirectCnt ? ITEM_SIZE : 0),
//                            pageSize);
//
//                        setLink(row, pageAddr, itemId);
//
//                        return 0;
//                    }
//
//                    off = off1;
//                    size = getPageEntrySize(pageAddr, off, SHOW_PAYLOAD_LEN | SHOW_LINK);
//                }
//            }
//        }

        // Move right all of the entries if possible to make the page as compact as possible to its tail.
        int prevOff = pageSize;

        final int start = directCnt - 1;
        int curOff = offs[start] >>> 8;
        int curEntrySize = getPageEntrySize(pageAddr, curOff, SHOW_PAYLOAD_LEN | SHOW_LINK);

        for (int i = start; i >= 0; i--) {
            assert curOff < prevOff : curOff;

            int delta = prevOff - (curOff + curEntrySize);

            int off = curOff;
            int entrySize = curEntrySize;

            if (delta != 0) { // Move right.
                assert delta > 0 : delta;

                if (row != null && delta >= newEntrySize) {
                    foundCnt2.increment();

                    int newEntryOff = curOff + curEntrySize;

                    writeRowData(pageAddr, newEntryOff, newEntrySize - ITEM_SIZE - PAYLOAD_LEN_SIZE, row, true);

                    int itemId = insertItem(pageAddr, newEntryOff, directCnt, indirectCnt, pageSize);

                    assert checkIndex(itemId): itemId;
                    assert getIndirectCount(pageAddr) <= getDirectCount(pageAddr);

                    // Update free space. If number of indirect items changed, then we were able to reuse an item slot.
                    setRealFreeSpace(pageAddr,
                        getRealFreeSpace(pageAddr) - newEntrySize + (getIndirectCount(pageAddr) != indirectCnt ? ITEM_SIZE : 0),
                        pageSize);

                    setLink(row, pageAddr, itemId);

                    if (rmvdCnt > 0)
                        clearRemoved(pageAddr);

                    return 0;
                }

                int itemId = offs[i] & 0xFF;

                setItem(pageAddr, itemId, directItemFromOffset(curOff + delta));

                for (int j = i - 1; j >= 0; j--) {
                    int offNext = offs[j] >>> 8;
                    int nextSize = getPageEntrySize(pageAddr, offNext, SHOW_PAYLOAD_LEN | SHOW_LINK);

                    if (offNext + nextSize == off) {
                        i--;

                        off = offNext;
                        entrySize += nextSize;

                        itemId = offs[j] & 0xFF;
                        setItem(pageAddr, itemId, directItemFromOffset(offNext + delta));
                    }
                    else {
                        curOff = offNext;
                        curEntrySize = nextSize;

                        break;
                    }
                }

                moveBytes(pageAddr, off, entrySize, delta, pageSize);

                off += delta;
            }
            else if (i > 0) {
                curOff = offs[i - 1] >>> 8;
                curEntrySize = getPageEntrySize(pageAddr, curOff, SHOW_PAYLOAD_LEN | SHOW_LINK);
            }

            prevOff = off;
        }

        if (rmvdCnt > 0)
            clearRemoved(pageAddr);

        return prevOff;
    }

    /**
     * Full-scan free space calculation procedure.
     *
     * @param pageAddr Page to scan.
     * @param pageSize Page size.
     * @return Actual free space in the buffer.
     */
    private int actualFreeSpace(long pageAddr, int pageSize) {
        int directCnt = getDirectCount(pageAddr);

        int entriesSize = 0;

        for (int i = 0; i < directCnt; i++) {
            int off = directItemToOffset(getItem(pageAddr, i));

            int entrySize = getPageEntrySize(pageAddr, off, SHOW_PAYLOAD_LEN | SHOW_LINK);

            entriesSize += entrySize;
        }

        return pageSize - ITEMS_OFF - entriesSize - (directCnt + getIndirectCount(pageAddr)) * ITEM_SIZE;
    }

    /**
     * @param addr Address.
     * @param off Offset.
     * @param cnt Count.
     * @param step Step.
     * @param pageSize Page size.
     */
    private void moveBytes(long addr, int off, int cnt, int step, int pageSize) {
        assert step != 0: step;
        assert off + step >= 0;
        assert off + step + cnt <= pageSize : "[off=" + off + ", step=" + step + ", cnt=" + cnt +
            ", cap=" + pageSize + ']';

        PageHandler.copyMemory(addr, addr, off, off + step, cnt);
    }

    /**
     * @param pageAddr Page address.
     * @param dataOff Data offset.
     * @param payloadSize Payload size.
     * @param row Data row.
     * @param newRow {@code False} if existing cache entry is updated, in this case skip key data write.
     * @throws IgniteCheckedException If failed.
     */
    private void writeRowData(
        long pageAddr,
        int dataOff,
        int payloadSize,
        CacheDataRow row,
        boolean newRow
    ) throws IgniteCheckedException {
        long addr = pageAddr + dataOff;

        if (newRow) {
            PageUtils.putShort(addr, 0, (short)payloadSize);
            addr += 2;

            addr += row.key().putValue(addr);
        }
        else
            addr += (2 + row.key().valueBytesLength(null));

        addr += row.value().putValue(addr);

        CacheVersionIO.write(addr, row.version(), false);
        addr += CacheVersionIO.size(row.version(), false);

        PageUtils.putLong(addr, 0, row.expireTime());
    }

    /**
     * @param pageAddr Page address.
     * @param dataOff Data offset.
     * @param payload Payload
     */
    private void writeRowData(
        long pageAddr,
        int dataOff,
        byte[] payload
    ) {
        PageUtils.putShort(pageAddr, dataOff, (short)payload.length);
        dataOff += 2;

        PageUtils.putBytes(pageAddr, dataOff, payload);
    }
}