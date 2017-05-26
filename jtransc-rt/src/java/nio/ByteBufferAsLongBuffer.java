/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package java.nio;

import libcore.io.Memory;

import java.nio.internal.SizeOf;

import java.nio.internal.ByteBufferAs;

abstract class ByteBufferAsLongBuffer extends LongBuffer implements ByteBufferAs {
    final ByteBuffer byteBuffer;
	final byte[] bytes;

    static LongBuffer asLongBuffer(ByteBuffer byteBuffer) {
        ByteBuffer slice = byteBuffer.slice();
        slice.order(byteBuffer.order());
		return create(slice, byteBuffer.isLittleEndian);
    }

	static private ByteBufferAsLongBuffer create(ByteBuffer byteBuffer, boolean isLittleEndian) {
		return isLittleEndian ? new ByteBufferAsLongBuffer.LE(byteBuffer) : new ByteBufferAsLongBuffer.BE(byteBuffer);
	}

	private ByteBufferAsLongBuffer createWithSameOrder(ByteBuffer byteBuffer) {
		return create(byteBuffer, order() == ByteOrder.LITTLE_ENDIAN);
	}

	private ByteBufferAsLongBuffer(ByteBuffer byteBuffer) {
        super(byteBuffer.capacity() / SizeOf.LONG);
        this.byteBuffer = byteBuffer;
        this.byteBuffer.clear();
		this.bytes = byteBuffer.array();
		init(byteBuffer.array());
	}

	private void init(byte[] data) {
	}

    @Override
    public LongBuffer asReadOnlyBuffer() {
        ByteBufferAsLongBuffer buf = (ByteBufferAsLongBuffer) byteBuffer.asReadOnlyBuffer().asLongBuffer();
        buf.limit = limit;
        buf.position = position;
        buf.mark = mark;
        buf.byteBuffer.order = byteBuffer.order;
        return buf;
    }

    @Override
    public LongBuffer compact() {
        if (byteBuffer.isReadOnly()) throw new ReadOnlyBufferException();
        byteBuffer.limit(limit * SizeOf.LONG);
        byteBuffer.position(position * SizeOf.LONG);
        byteBuffer.compact();
        byteBuffer.clear();
        position = limit - position;
        limit = capacity;
        mark = UNSET_MARK;
        return this;
    }

    @Override
    public LongBuffer duplicate() {
        ByteBuffer bb = byteBuffer.duplicate().order(byteBuffer.order());
        ByteBufferAsLongBuffer buf = createWithSameOrder(bb);
        buf.limit = limit;
        buf.position = position;
        buf.mark = mark;
        return buf;
    }

    @Override
    public long get() {
        if (position == limit) throw new BufferUnderflowException();
        return byteBuffer.getLong(position++ * SizeOf.LONG);
    }

    @Override
    public boolean isDirect() {
        return byteBuffer.isDirect();
    }

    @Override
    public boolean isReadOnly() {
        return byteBuffer.isReadOnly();
    }

    @Override
    public ByteOrder order() {
        return byteBuffer.order();
    }

    @Override long[] protectedArray() {
        throw new UnsupportedOperationException();
    }

    @Override int protectedArrayOffset() {
        throw new UnsupportedOperationException();
    }

    @Override boolean protectedHasArray() {
        return false;
    }

	@Override
	public LongBuffer put(long c) {
		if (position == limit) {
			throw new BufferOverflowException();
		}
		byteBuffer.putLong(position++ * SizeOf.LONG, c);
		return this;
	}

    @Override
    public LongBuffer slice() {
        byteBuffer.limit(limit * SizeOf.LONG);
        byteBuffer.position(position * SizeOf.LONG);
        ByteBuffer bb = byteBuffer.slice().order(byteBuffer.order());
        LongBuffer result = createWithSameOrder(bb);
        byteBuffer.clear();
        return result;
    }

	@Override
	public ByteBuffer getByteBuffer() {
		return byteBuffer;
	}

	final static public class LE extends ByteBufferAsLongBuffer {
		LE(ByteBuffer byteBuffer) {
			super(byteBuffer);
		}

		@Override
		public long get(int index) {
			return Memory.peekAlignedLongLE(bytes, index);
		}

		@Override
		public LongBuffer put(int index, long c) {
			Memory.pokeAlignedLongLE(bytes, index, c);
			return this;
		}
	}

	final static public class BE extends ByteBufferAsLongBuffer {
		BE(ByteBuffer byteBuffer) {
			super(byteBuffer);
		}

		@Override
		public long get(int index) {
			return Memory.peekAlignedLongBE(bytes, index);
		}

		@Override
		public LongBuffer put(int index, long c) {
			Memory.pokeAlignedLongBE(bytes, index, c);
			return this;
		}
	}
}