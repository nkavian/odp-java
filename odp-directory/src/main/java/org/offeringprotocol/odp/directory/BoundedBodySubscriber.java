package org.offeringprotocol.odp.directory;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int maximumBytes;
    private Flow.Subscription subscription;
    private int received;
    private boolean finished;

    BoundedBodySubscriber(int maximumBytes) {
        this.maximumBytes = maximumBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        delegate.onSubscribe(value);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (finished) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > maximumBytes - received) {
                subscription.cancel();
                onError(new IllegalStateException("Directory response exceeds its byte limit"));
                return;
            }
            received += buffer.remaining();
        }
        delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable failure) {
        if (!finished) {
            finished = true;
            delegate.onError(failure);
        }
    }

    @Override
    public void onComplete() {
        if (!finished) {
            finished = true;
            delegate.onComplete();
        }
    }
}
