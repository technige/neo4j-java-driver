/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.driver.internal.async;

import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.pool.ChannelPool;
import io.netty.util.internal.ConcurrentSet;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.neo4j.driver.internal.BoltServerAddress;
import org.neo4j.driver.internal.async.inbound.InboundMessageDispatcher;
import org.neo4j.driver.internal.handlers.NoOpResponseHandler;
import org.neo4j.driver.internal.spi.ResponseHandler;
import org.neo4j.driver.internal.util.FakeClock;
import org.neo4j.driver.internal.util.ServerVersion;

import static java.util.Collections.emptyMap;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.driver.internal.async.ChannelAttributes.messageDispatcher;
import static org.neo4j.driver.internal.async.ChannelAttributes.setMessageDispatcher;
import static org.neo4j.driver.internal.async.ChannelAttributes.terminationReason;
import static org.neo4j.driver.internal.logging.DevNullLogging.DEV_NULL_LOGGING;
import static org.neo4j.driver.internal.messaging.ResetMessage.RESET;
import static org.neo4j.driver.internal.metrics.InternalAbstractMetrics.DEV_NULL_METRICS;
import static org.neo4j.driver.internal.util.Iterables.single;
import static org.neo4j.driver.v1.util.DaemonThreadFactory.daemon;

public class NettyConnectionTest
{
    private static final NoOpResponseHandler NO_OP_HANDLER = NoOpResponseHandler.INSTANCE;

    private ExecutorService executor;
    private EventLoop eventLoop;

    @After
    public void tearDown() throws Exception
    {
        shutdownEventLoop();
    }

    @Test
    public void shouldBeOpenAfterCreated()
    {
        NettyConnection connection = newConnection( newChannel() );
        assertTrue( connection.isOpen() );
    }

    @Test
    public void shouldNotBeOpenAfterRelease()
    {
        NettyConnection connection = newConnection( newChannel() );
        connection.release();
        assertFalse( connection.isOpen() );
    }

    @Test
    public void shouldSendResetOnRelease()
    {
        EmbeddedChannel channel = newChannel();
        NettyConnection connection = newConnection( channel );

        connection.release();
        channel.runPendingTasks();

        assertEquals( 1, channel.outboundMessages().size() );
        assertEquals( RESET, channel.readOutbound() );
    }

    @Test
    public void shouldEnqueueRunHandlerFromEventLoopThread() throws Exception
    {
        testWriteInEventLoop( "RunTestEventLoop",
                connection -> connection.run( "RETURN 1", emptyMap(), NO_OP_HANDLER, NO_OP_HANDLER ) );
    }

    @Test
    public void shouldWriteRunAndFlushInEventLoopThread() throws Exception
    {
        testWriteInEventLoop( "RunAndFlushTestEventLoop",
                connection -> connection.runAndFlush( "RETURN 1", emptyMap(), NO_OP_HANDLER, NO_OP_HANDLER ) );
    }

    @Test
    public void shouldWriteForceReleaseInEventLoopThread() throws Exception
    {
        testWriteInEventLoop( "ReleaseTestEventLoop", NettyConnection::release );
    }

    @Test
    public void shouldEnableAutoReadWhenReleased()
    {
        EmbeddedChannel channel = newChannel();
        channel.config().setAutoRead( false );

        NettyConnection connection = newConnection( channel );

        connection.release();
        channel.runPendingTasks();

        assertTrue( channel.config().isAutoRead() );
    }

    @Test
    public void shouldNotDisableAutoReadWhenReleased()
    {
        EmbeddedChannel channel = newChannel();
        channel.config().setAutoRead( true );

        NettyConnection connection = newConnection( channel );

        connection.release();
        connection.disableAutoRead(); // does nothing on released connection
        assertTrue( channel.config().isAutoRead() );
    }

    @Test
    public void shouldNotRunWhenReleased()
    {
        ResponseHandler runHandler = mock( ResponseHandler.class );
        ResponseHandler pullAllHandler = mock( ResponseHandler.class );
        NettyConnection connection = newConnection( newChannel() );

        connection.release();
        connection.run( "RETURN 1", emptyMap(), runHandler, pullAllHandler );

        ArgumentCaptor<IllegalStateException> failureCaptor = ArgumentCaptor.forClass( IllegalStateException.class );
        verify( runHandler ).onFailure( failureCaptor.capture() );
        assertConnectionReleasedError( failureCaptor.getValue() );
    }

    @Test
    public void shouldNotRunAndFlushWhenReleased()
    {
        ResponseHandler runHandler = mock( ResponseHandler.class );
        ResponseHandler pullAllHandler = mock( ResponseHandler.class );
        NettyConnection connection = newConnection( newChannel() );

        connection.release();
        connection.runAndFlush( "RETURN 1", emptyMap(), runHandler, pullAllHandler );

        ArgumentCaptor<IllegalStateException> failureCaptor = ArgumentCaptor.forClass( IllegalStateException.class );
        verify( runHandler ).onFailure( failureCaptor.capture() );
        assertConnectionReleasedError( failureCaptor.getValue() );
    }

    @Test
    public void shouldNotRunWhenTerminated()
    {
        ResponseHandler runHandler = mock( ResponseHandler.class );
        ResponseHandler pullAllHandler = mock( ResponseHandler.class );
        NettyConnection connection = newConnection( newChannel() );

        connection.terminateAndRelease( "42" );
        connection.run( "RETURN 1", emptyMap(), runHandler, pullAllHandler );

        ArgumentCaptor<IllegalStateException> failureCaptor = ArgumentCaptor.forClass( IllegalStateException.class );
        verify( runHandler ).onFailure( failureCaptor.capture() );
        assertConnectionTerminatedError( failureCaptor.getValue() );
    }

    @Test
    public void shouldNotRunAndFlushWhenTerminated()
    {
        ResponseHandler runHandler = mock( ResponseHandler.class );
        ResponseHandler pullAllHandler = mock( ResponseHandler.class );
        NettyConnection connection = newConnection( newChannel() );

        connection.terminateAndRelease( "42" );
        connection.runAndFlush( "RETURN 1", emptyMap(), runHandler, pullAllHandler );

        ArgumentCaptor<IllegalStateException> failureCaptor = ArgumentCaptor.forClass( IllegalStateException.class );
        verify( runHandler ).onFailure( failureCaptor.capture() );
        assertConnectionTerminatedError( failureCaptor.getValue() );
    }

    @Test
    public void shouldReturnServerAddressWhenReleased()
    {
        EmbeddedChannel channel = newChannel();
        BoltServerAddress address = new BoltServerAddress( "host", 4242 );
        ChannelAttributes.setServerAddress( channel, address );

        NettyConnection connection = newConnection( channel );
        connection.release();

        assertEquals( address, connection.serverAddress() );
    }

    @Test
    public void shouldReturnServerVersionWhenReleased()
    {
        EmbeddedChannel channel = newChannel();
        ServerVersion version = ServerVersion.v3_2_0;
        ChannelAttributes.setServerVersion( channel, version );

        NettyConnection connection = newConnection( channel );
        connection.release();

        assertEquals( version, connection.serverVersion() );
    }

    @Test
    public void shouldReturnSameCompletionStageFromRelease()
    {
        EmbeddedChannel channel = newChannel();
        NettyConnection connection = newConnection( channel );

        CompletionStage<Void> releaseStage1 = connection.release();
        CompletionStage<Void> releaseStage2 = connection.release();
        CompletionStage<Void> releaseStage3 = connection.release();

        channel.runPendingTasks();

        // RESET should be send only once
        assertEquals( 1, channel.outboundMessages().size() );
        assertEquals( RESET, channel.outboundMessages().poll() );

        // all returned stages should be the same
        assertEquals( releaseStage1, releaseStage2 );
        assertEquals( releaseStage2, releaseStage3 );
    }

    @Test
    public void shouldEnableAutoRead()
    {
        EmbeddedChannel channel = newChannel();
        channel.config().setAutoRead( false );
        NettyConnection connection = newConnection( channel );

        connection.enableAutoRead();

        assertTrue( channel.config().isAutoRead() );
    }

    @Test
    public void shouldDisableAutoRead()
    {
        EmbeddedChannel channel = newChannel();
        channel.config().setAutoRead( true );
        NettyConnection connection = newConnection( channel );

        connection.disableAutoRead();

        assertFalse( channel.config().isAutoRead() );
    }

    @Test
    public void shouldSetTerminationReasonOnChannelWhenTerminated()
    {
        EmbeddedChannel channel = newChannel();
        NettyConnection connection = newConnection( channel );

        String reason = "Something really bad has happened";
        connection.terminateAndRelease( reason );

        assertEquals( reason, terminationReason( channel ) );
    }

    @Test
    public void shouldCloseChannelWhenTerminated()
    {
        EmbeddedChannel channel = newChannel();
        NettyConnection connection = newConnection( channel );
        assertTrue( channel.isActive() );

        connection.terminateAndRelease( "test" );

        assertFalse( channel.isActive() );
    }

    @Test
    public void shouldReleaseChannelWhenTerminated()
    {
        EmbeddedChannel channel = newChannel();
        ChannelPool pool = mock( ChannelPool.class );
        NettyConnection connection = newConnection( channel, pool );
        verify( pool, never() ).release( any() );

        connection.terminateAndRelease( "test" );

        verify( pool ).release( channel );
    }

    @Test
    public void shouldNotReleaseChannelMultipleTimesWhenTerminatedMultipleTimes()
    {
        EmbeddedChannel channel = newChannel();
        ChannelPool pool = mock( ChannelPool.class );
        NettyConnection connection = newConnection( channel, pool );
        verify( pool, never() ).release( any() );

        connection.terminateAndRelease( "reason 1" );
        connection.terminateAndRelease( "reason 2" );
        connection.terminateAndRelease( "reason 3" );

        // channel is terminated with the first termination reason
        assertEquals( "reason 1", terminationReason( channel ) );
        // channel is released to the pool only once
        verify( pool ).release( channel );
    }

    @Test
    public void shouldNotReleaseAfterTermination()
    {
        EmbeddedChannel channel = newChannel();
        ChannelPool pool = mock( ChannelPool.class );
        NettyConnection connection = newConnection( channel, pool );
        verify( pool, never() ).release( any() );

        connection.terminateAndRelease( "test" );
        CompletionStage<Void> releaseStage = connection.release();

        // release stage should be completed immediately
        assertTrue( releaseStage.toCompletableFuture().isDone() );
        // channel is released to the pool only once
        verify( pool ).release( channel );
    }

    @Test
    public void shouldSendResetMessageWhenReset()
    {
        EmbeddedChannel channel = newChannel();
        NettyConnection connection = newConnection( channel );

        connection.reset();
        channel.runPendingTasks();

        assertEquals( 1, channel.outboundMessages().size() );
        assertEquals( RESET, channel.readOutbound() );
    }

    @Test
    public void shouldCompleteResetFutureWhenSuccessResponseArrives()
    {
        EmbeddedChannel channel = newChannel();
        NettyConnection connection = newConnection( channel );

        CompletableFuture<Void> resetFuture = connection.reset().toCompletableFuture();
        channel.runPendingTasks();
        assertFalse( resetFuture.isDone() );

        messageDispatcher( channel ).handleSuccessMessage( emptyMap() );
        assertTrue( resetFuture.isDone() );
        assertFalse( resetFuture.isCompletedExceptionally() );
    }

    @Test
    public void shouldCompleteResetFutureWhenFailureResponseArrives()
    {
        EmbeddedChannel channel = newChannel();
        NettyConnection connection = newConnection( channel );

        CompletableFuture<Void> resetFuture = connection.reset().toCompletableFuture();
        channel.runPendingTasks();
        assertFalse( resetFuture.isDone() );

        messageDispatcher( channel ).handleFailureMessage( "Neo.TransientError.Transaction.Terminated", "Message" );
        assertTrue( resetFuture.isDone() );
        assertFalse( resetFuture.isCompletedExceptionally() );
    }

    @Test
    public void shouldDoNothingInResetWhenClosed()
    {
        EmbeddedChannel channel = newChannel();
        NettyConnection connection = newConnection( channel );

        connection.release();
        channel.runPendingTasks();

        CompletableFuture<Void> resetFuture = connection.reset().toCompletableFuture();
        channel.runPendingTasks();

        assertEquals( 1, channel.outboundMessages().size() );
        assertEquals( RESET, channel.readOutbound() );
        assertTrue( resetFuture.isDone() );
        assertFalse( resetFuture.isCompletedExceptionally() );
    }

    @Test
    public void shouldMuteAckFailureWhenReset()
    {
        InboundMessageDispatcher messageDispatcher = mock( InboundMessageDispatcher.class );
        EmbeddedChannel channel = newChannel( messageDispatcher );
        NettyConnection connection = newConnection( channel );

        connection.reset();
        channel.runPendingTasks();

        verify( messageDispatcher ).muteAckFailure();
    }

    @Test
    public void shouldEnableAutoReadWhenDoingReset()
    {
        EmbeddedChannel channel = newChannel();
        channel.config().setAutoRead( false );
        NettyConnection connection = newConnection( channel );

        connection.reset();
        channel.runPendingTasks();

        assertTrue( channel.config().isAutoRead() );
    }

    private void testWriteInEventLoop( String threadName, Consumer<NettyConnection> action ) throws Exception
    {
        EmbeddedChannel channel = spy( new EmbeddedChannel() );
        initializeEventLoop( channel, threadName );
        ThreadTrackingInboundMessageDispatcher dispatcher = new ThreadTrackingInboundMessageDispatcher( channel );
        setMessageDispatcher( channel, dispatcher );

        NettyConnection connection = newConnection( channel );
        action.accept( connection );

        shutdownEventLoop();
        assertThat( single( dispatcher.queueThreadNames ), startsWith( threadName ) );
    }

    private void initializeEventLoop( Channel channel, String namePrefix )
    {
        executor = Executors.newSingleThreadExecutor( daemon( namePrefix ) );
        eventLoop = new DefaultEventLoop( executor );
        when( channel.eventLoop() ).thenReturn( eventLoop );
    }

    private void shutdownEventLoop() throws Exception
    {
        if ( eventLoop != null )
        {
            eventLoop.shutdownGracefully();
        }
        if ( executor != null )
        {
            executor.shutdown();
            assertTrue( executor.awaitTermination( 30, TimeUnit.SECONDS ) );
        }
    }

    private static EmbeddedChannel newChannel()
    {
        EmbeddedChannel channel = new EmbeddedChannel();
        InboundMessageDispatcher messageDispatcher = new InboundMessageDispatcher( channel, DEV_NULL_LOGGING );
        ChannelAttributes.setMessageDispatcher( channel, messageDispatcher );
        return channel;
    }

    private static EmbeddedChannel newChannel( InboundMessageDispatcher messageDispatcher )
    {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelAttributes.setMessageDispatcher( channel, messageDispatcher );
        return channel;
    }

    private static NettyConnection newConnection( Channel channel )
    {
        return newConnection( channel, mock( ChannelPool.class ) );
    }

    private static NettyConnection newConnection( Channel channel, ChannelPool pool )
    {
        return new NettyConnection( channel, pool, new FakeClock(), DEV_NULL_METRICS );
    }

    private static void assertConnectionReleasedError( IllegalStateException e )
    {
        assertThat( e.getMessage(), startsWith( "Connection has been released" ) );
    }

    private static void assertConnectionTerminatedError( IllegalStateException e )
    {
        assertThat( e.getMessage(), startsWith( "Connection has been terminated" ) );
    }

    private static class ThreadTrackingInboundMessageDispatcher extends InboundMessageDispatcher
    {

        final Set<String> queueThreadNames = new ConcurrentSet<>();
        ThreadTrackingInboundMessageDispatcher( Channel channel )
        {
            super( channel, DEV_NULL_LOGGING );
        }

        @Override
        public void queue( ResponseHandler handler )
        {
            queueThreadNames.add( Thread.currentThread().getName() );
            super.queue( handler );
        }

    }
}
