/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.stream.ChunkedNioFile;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * @author Stephane Maldini
 */
public interface NettyOutbound extends Publisher<Void> {

	/**
	 * Return the assigned {@link ByteBufAllocator}.
	 *
	 * @return the {@link ByteBufAllocator}
	 */
	ByteBufAllocator alloc();

	/**
	 * Return a never completing {@link Mono} after this {@link NettyOutbound#then()} has
	 * completed.
	 *
	 * @return a never completing {@link Mono} after this {@link NettyOutbound#then()} has
	 * completed.
	 */
	default Mono<Void> neverComplete() {
		return then(Mono.never()).then();
	}

	/**
	 * Provide a new {@link NettyOutbound} scoped configuration for sending. The
	 * {@link NettyPipeline.SendOptions} changes will apply to the next written object or
	 * {@link Publisher}.
	 *
	 * @param configurator the callback invoked to retrieve send configuration
	 *
	 * @return {@code this} instance
	 */
	default NettyOutbound options(Consumer<? super NettyPipeline.SendOptions> configurator) {
		return withConnection(c -> c.channel()
		                            .pipeline()
		                            .fireUserEventTriggered(new NettyPipeline.SendOptionsChangeEvent(configurator)));
	}

	/**
	 * Send data to the peer, listen for any error on write and close on terminal signal
	 * (complete|error). <p>A new {@link NettyOutbound} type (or the same) for typed send
	 * sequences. An implementor can therefore specialize the Outbound after a first after
	 * a prepending data publisher.
	 * Note: Nesting any send* method is not supported.
	 *
	 * @param dataStream the dataStream publishing OUT items to write on this channel
	 *
	 * @return A new {@link NettyOutbound} to append further send. It will emit a complete
	 * signal successful sequence write (e.g. after "flush") or  any error during write.
	 */
	default NettyOutbound send(Publisher<? extends ByteBuf> dataStream) {
		return sendObject(dataStream);
	}

	/**
	 * Send bytes to the peer, listen for any error on write and close on terminal
	 * signal (complete|error). If more than one publisher is attached (multiple calls to
	 * send()) completion occurs after all publishers complete.
	 * Note: Nesting any send* method is not supported.
	 *
	 * @param dataStream the dataStream publishing Buffer items to write on this channel
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendByteArray(Publisher<? extends byte[]> dataStream) {
		return send(ReactorNetty.publisherOrScalarMap(dataStream, Unpooled::wrappedBuffer));
	}

	/**
	 * Send content from given {@link Path} using
	 * {@link java.nio.channels.FileChannel#transferTo(long, long, WritableByteChannel)}
	 * support. If the system supports it and the path resolves to a local file
	 * system {@link File} then transfer will use zero-byte copy
	 * to the peer.
	 * <p>It will
	 * listen for any error on
	 * write and close
	 * on terminal signal (complete|error). If more than one publisher is attached
	 * (multiple calls to send()) completion occurs after all publishers complete.
	 * <p>
	 * Note: this will emit {@link io.netty.channel.FileRegion} in the outbound
	 * {@link io.netty.channel.ChannelPipeline}
	 * Note: Nesting any send* method is not supported.
	 *
	 * @param file the file Path
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendFile(Path file) {
		try {
			return sendFile(file, 0L, Files.size(file));
		}
		catch (IOException e) {
			return then(Mono.error(e));
		}
	}

	/**
	 * Send content from given {@link Path} using
	 * {@link java.nio.channels.FileChannel#transferTo(long, long, WritableByteChannel)}
	 * support. If the system supports it and the path resolves to a local file
	 * system {@link File} then transfer will use zero-byte copy
	 * to the peer.
	 * <p>It will
	 * listen for any error on
	 * write and close
	 * on terminal signal (complete|error). If more than one publisher is attached
	 * (multiple calls to send()) completion occurs after all publishers complete.
	 * <p>
	 * Note: this will emit {@link io.netty.channel.FileRegion} in the outbound
	 * {@link io.netty.channel.ChannelPipeline}
	 * Note: Nesting any send* method is not supported.
	 *
	 * @param file the file Path
	 * @param position where to start
	 * @param count how much to transfer
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendFile(Path file, long position, long count) {
		Objects.requireNonNull(file, "filepath");

		return sendUsing(() -> FileChannel.open(file, StandardOpenOption.READ),
				(c, fc) -> {
					if (ReactorNetty.mustChunkFileTransfer(c, file)) {
						ReactorNetty.addChunkedWriter(c);
						try {
							return new ChunkedNioFile(fc, position, count, 1024);
						}
						catch (Exception ioe) {
							throw Exceptions.propagate(ioe);
						}
					}
					return new DefaultFileRegion(fc, position, count);
				},
				ReactorNetty.fileCloser);
	}

	/**
	 * Send content from given {@link Path} using chunked read/write. <p>It will listen
	 * for any error on write and close on terminal signal (complete|error). If more than
	 * one publisher is attached (multiple calls to send()) completion occurs after all
	 * publishers complete.
	 * <p>
	 * Note: this will emit {@link io.netty.channel.FileRegion} in the outbound {@link
	 * io.netty.channel.ChannelPipeline}
	 * Note: Nesting any send* method is not supported.
	 *
	 * @param file the file Path
	 * @param position where to start
	 * @param count how much to transfer
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendFileChunked(Path file, long position, long count) {
		Objects.requireNonNull(file, "filepath");

		return sendUsing(() -> FileChannel.open(file, StandardOpenOption.READ),
				(c, fc) -> {
					ReactorNetty.addChunkedWriter(c);
					try {
						return new ChunkedNioFile(fc, position, count, 1024);
					}
					catch (Exception e) {
						throw Exceptions.propagate(e);
					}
				},
				ReactorNetty.fileCloser);
	}

	/**
	 * Send data to the peer, listen for any error on write and close on terminal signal
	 * (complete|error).Each individual {@link Publisher} completion will flush
	 * the underlying IO runtime.
	 * Note: Nesting any send* method is not supported.
	 *
	 * @param dataStreams the dataStream publishing OUT items to write on this channel
	 *
	 * @return A {@link Mono} to signal successful sequence write (e.g. after "flush") or
	 * any error during write
	 */
	default NettyOutbound sendGroups(Publisher<? extends Publisher<? extends ByteBuf>> dataStreams) {
		return then(Flux.from(dataStreams)
		           .concatMapDelayError(this::send, false, 32)
		           .then());
	}

	/**
	 * Send an object through netty pipeline. If type of Publisher, send all signals,
	 * flushing on complete by default. Write occur in FIFO sequence.
	 * Note: Nesting any send* method is not supported.
	 *
	 * @param dataStream the dataStream publishing items to write on this channel
	 * or a simple pojo supported by configured netty handlers
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	NettyOutbound sendObject(Publisher<?> dataStream);

	/**
	 * Send data to the peer, listen for any error on write and close on terminal signal
	 * (complete|error).
	 * Note: Nesting any send* method is not supported.
	 *
	 * @param message the object to publish
	 *
	 * @return A {@link Mono} to signal successful sequence write (e.g. after "flush") or
	 * any error during write
	 */
	NettyOutbound sendObject(Object message);

	/**
	 * Send String to the peer, listen for any error on write and close on terminal signal
	 * (complete|error). If more than one publisher is attached (multiple calls to send())
	 * completion occurs after all publishers complete.
	 * Note: Nesting any send* method is not supported.
	 *
	 * @param dataStream the dataStream publishing Buffer items to write on this channel
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendString(Publisher<? extends String> dataStream) {
		return sendString(dataStream, Charset.defaultCharset());
	}

	/**
	 * Send String to the peer, listen for any error on write and close on terminal signal
	 * (complete|error). If more than one publisher is attached (multiple calls to send())
	 * completion occurs after all publishers complete.
	 * Note: Nesting any send* method is not supported.
	 *
	 * @param dataStream the dataStream publishing Buffer items to write on this channel
	 * @param charset the encoding charset
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendString(Publisher<? extends String> dataStream,
			Charset charset) {
		return sendObject(ReactorNetty.publisherOrScalarMap(dataStream, s -> alloc()
				.buffer()
				.writeBytes(s.getBytes(charset))));
	}

	/**
	 * Bind a send to a starting/cleanup lifecycle
	 * Note: Nesting any send* method is not supported.
	 *
	 * @param sourceInput state generator
	 * @param mappedInput input to send
	 * @param sourceCleanup state cleaner
	 * @param <S> state type
	 *
	 * @return a new {@link NettyOutbound}
	 */
	<S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
			BiFunction<? super Connection, ? super S, ?> mappedInput,
			Consumer<? super S> sourceCleanup);

	/**
	 * Subscribe a {@code Void} subscriber to this outbound and trigger all eventual
	 * parent outbound send.
	 *
	 * @param s the {@link Subscriber} to listen for send sequence completion/failure
	 */
	@Override
	default void subscribe(Subscriber<? super Void> s) {
		then().subscribe(s);
	}

	/**
	 * Obtain a {@link Mono} of pending outbound(s) write completion.
	 *
	 * @return a {@link Mono} of pending outbound(s) write completion
	 */
	default Mono<Void> then() {
		return Mono.empty();
	}

	/**
	 * Append a {@link Publisher} task such as a Mono and return a new
	 * {@link NettyOutbound} to sequence further send.
	 *
	 * @param other the {@link Publisher} to subscribe to when this pending outbound
	 * {@link #then()} is complete;
	 *
	 * @return a new {@link NettyOutbound} that
	 */
	default NettyOutbound then(Publisher<Void> other) {
		return new ReactorNetty.OutboundThen(this, other);
	}

	/**
	 * Call the passed callback with a {@link Connection} to operate on the underlying
	 * {@link Channel} state.
	 *
	 * @param withConnection connection callback
	 *
	 * @return the {@link Connection}
	 */
	NettyOutbound withConnection(Consumer<? super Connection> withConnection);
}
