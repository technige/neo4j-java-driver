/*
 * Copyright (c) 2002-2018 Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.driver.internal.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;

import org.neo4j.driver.internal.messaging.Message;
import org.neo4j.driver.internal.messaging.PackStreamMessageFormatV1;
import org.neo4j.driver.internal.packstream.PackOutput;

public final class MessageToByteBufWriter
{
    private MessageToByteBufWriter()
    {
    }

    public static ByteBuf asByteBuf( Message message )
    {
        try
        {
            ByteBuf buf = Unpooled.buffer();
            ByteBufOutput output = new ByteBufOutput( buf );
            new PackStreamMessageFormatV1.Writer( output, true ).write( message );
            return buf;
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    private static class ByteBufOutput implements PackOutput
    {
        final ByteBuf buf;

        ByteBufOutput( ByteBuf buf )
        {
            this.buf = buf;
        }

        @Override
        public PackOutput writeByte( byte value )
        {
            buf.writeByte( value );
            return this;
        }

        @Override
        public PackOutput writeBytes( byte[] data )
        {
            buf.writeBytes( data );
            return this;
        }

        @Override
        public PackOutput writeShort( short value )
        {
            buf.writeShort( value );
            return this;
        }

        @Override
        public PackOutput writeInt( int value )
        {
            buf.writeInt( value );
            return this;
        }

        @Override
        public PackOutput writeLong( long value )
        {
            buf.writeLong( value );
            return this;
        }

        @Override
        public PackOutput writeDouble( double value )
        {
            buf.writeDouble( value );
            return this;
        }
    }
}
