/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.driver.remote;

import org.apache.commons.configuration.Configuration;
import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.Result;
import org.apache.tinkerpop.gremlin.driver.ResultSet;
import org.apache.tinkerpop.gremlin.process.remote.traversal.AbstractRemoteTraversal;
import org.apache.tinkerpop.gremlin.process.remote.traversal.DefaultRemoteTraverser;
import org.apache.tinkerpop.gremlin.process.remote.traversal.RemoteTraversalSideEffects;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.EmptyTraverser;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.util.Attachable;

import java.util.Iterator;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A {@link AbstractRemoteTraversal} implementation for the Gremlin Driver.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class DriverRemoteTraversal<S, E> extends AbstractRemoteTraversal<S, E> {

    private final Iterator<Traverser.Admin<E>> traversers;
    private Traverser.Admin<E> lastTraverser = EmptyTraverser.instance();
    private final RemoteTraversalSideEffects sideEffects;

    public DriverRemoteTraversal(final ResultSet rs, final Client client, final boolean attach, final Optional<Configuration> conf) {
        // attaching is really just for testing purposes. it doesn't make sense in any real-world scenario as it would
        // require that the client have access to the Graph instance that produced the result. tests need that
        // attachment process to properly execute in full hence this little hack.
        if (attach) {
            if (!conf.isPresent()) throw new IllegalStateException("Traverser can't be reattached for testing");
            final Graph graph = ((Supplier<Graph>) conf.get().getProperty("hidden.for.testing.only")).get();
            this.traversers = new AttachingTraverserIterator<>(rs.iterator(), graph);
        } else {
            this.traversers = new TraverserIterator<>(rs.iterator());
        }

        this.sideEffects = new DriverRemoteTraversalSideEffects(
                client,
                rs.getOriginalRequestMessage().getRequestId(),
                rs.getHost());
    }

    @Override
    public RemoteTraversalSideEffects getSideEffects() {
        return this.sideEffects;
    }

    @Override
    public boolean hasNext() {
        return this.lastTraverser.bulk() > 0L || this.traversers.hasNext();
    }

    @Override
    public E next() {
        if (0L == this.lastTraverser.bulk())
            this.lastTraverser = this.traversers.next();
        if (1L == this.lastTraverser.bulk()) {
            final E temp = this.lastTraverser.get();
            this.lastTraverser = EmptyTraverser.instance();
            return temp;
        } else {
            this.lastTraverser.setBulk(this.lastTraverser.bulk() - 1L);
            return this.lastTraverser.get();
        }
    }

    @Override
    public Traverser.Admin<E> nextTraverser() {
        if (0L == this.lastTraverser.bulk())
            return this.traversers.next();
        else {
            final Traverser.Admin<E> temp = this.lastTraverser;
            this.lastTraverser = EmptyTraverser.instance();
            return temp;
        }
    }

    static class TraverserIterator<E> implements Iterator<Traverser.Admin<E>> {

        private final Iterator<Result> inner;

        public TraverserIterator(final Iterator<Result> resultIterator) {
            inner = resultIterator;
        }

        @Override
        public boolean hasNext() {
            return inner.hasNext();
        }

        @Override
        public Traverser.Admin<E> next() {
            return (DefaultRemoteTraverser<E>) inner.next().getObject();
        }
    }

    static class AttachingTraverserIterator<E> extends TraverserIterator<E> {
        private final Graph graph;

        public AttachingTraverserIterator(final Iterator<Result> resultIterator, final Graph graph) {
            super(resultIterator);
            this.graph = graph;
        }

        @Override
        public Traverser.Admin<E> next() {
            final Traverser.Admin<E> traverser = super.next();
            if (traverser.get() instanceof Attachable && !(traverser.get() instanceof Property))
                traverser.set((E) ((Attachable<Element>) traverser.get()).attach(Attachable.Method.get(graph)));
            return traverser;
        }
    }
}
