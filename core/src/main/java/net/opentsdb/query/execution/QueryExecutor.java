// This file is part of OpenTSDB.
// Copyright (C) 2016  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.query.execution;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.stumbleupon.async.Deferred;

import io.opentracing.Span;
import net.opentsdb.exceptions.RemoteQueryExecutionException;
import net.opentsdb.query.context.QueryContext;
import net.opentsdb.query.execution.graph.ExecutionGraphNode;
import net.opentsdb.query.pojo.TimeSeriesQuery;
import net.opentsdb.utils.Deferreds;

/**
 * A base query executor that may spawn a tree of sub executors for processing.
 * The executor can return data of any type.
 * 
 * @param <T> The type of data returned by the executor.
 * 
 * @since 3.0
 */
public abstract class QueryExecutor<T> {
  private static final Logger LOG = LoggerFactory.getLogger(QueryExecutor.class);

  protected final ExecutionGraphNode node;
  
  /** Set to true when the upstream caller has marked this stream as completed 
   * (or cancelled) */
  protected final AtomicBoolean completed;

  /** The list of outstanding executions to be used when closing. */
  protected final Set<QueryExecution<T>> outstanding_executions;
  
  /** Downstream executors to close. */
  protected List<QueryExecutor<T>> downstream_executors;
  
  /**
   * Default ctor.
   * @param node A node to pull configuration from such as the ID and default
   * config.
   * @throws IllegalArgumentException if the node was null.
   */
  public QueryExecutor(final ExecutionGraphNode node) {
    if (node == null) {
      throw new IllegalArgumentException("Node cannot be null.");
    }
    this.node = node;
    completed = new AtomicBoolean();
    outstanding_executions = Sets.<QueryExecution<T>>newConcurrentHashSet();
  }
  
  /**
   * Runs the given query.
   * @param query A non-null query to execute.
   * @param upstream_span An optional upstream tracer span.
   * @return A query execution object that will contain a deferred to wait on
   * for a response.
   * @throws IllegalArgumentException if the query was null.
   * @throws RejectedExecutionException (in the deferred) if the query could not
   * be executed due to an error such as already being cancelled.
   * @throws RemoteQueryExecutionException (in the deferred) if the remote call
   * failed.
   */
  public abstract QueryExecution<T> executeQuery(final QueryContext context,
                                                 final TimeSeriesQuery query,
                                                 final Span upstream_span);
  
  /**
   * Method called to close and release all resources. The default simply cancels
   * any outstanding requests then closes any downstream executors.
   * @return A non-null deferred that may contain a null response or an exception
   * on completion.
   */
  public Deferred<Object> close() {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Closing executor: " + this);
    }
    cancelOutstanding();
    if (downstream_executors != null) {
      if (downstream_executors.size() == 1) {
        return downstream_executors.iterator().next().close();
      }
      final List<Deferred<Object>> deferreds = 
          Lists.newArrayListWithExpectedSize(downstream_executors.size());
      for (final QueryExecutor<T> executor : downstream_executors) {
        deferreds.add(executor.close());
      }
      return Deferred.group(deferreds).addCallback(Deferreds.NULL_GROUP_CB);
    }
    return Deferred.fromResult(null);
  }
  
  public String id() {
    return node.getExecutorId();
  }
  
  /**
   * Iterates over outstanding executions and cancels them. There may be a race
   * condition when canceling which is why the exception is caught and logged.
   */
  protected void cancelOutstanding() {
    for (final QueryExecution<T> exec : outstanding_executions) {
      try {
        exec.cancel();
      } catch (Exception e) {
        LOG.error("Exception while closing executor", e);
      }
    }
  }
  
  /**
   * Adds a downstream executor to the set or closing at the end.
   * @param executor A non-null executor to add.
   * @throws IllegalArgumentException if the executor was null.
   */
  @SuppressWarnings("unchecked")
  protected void registerDownstreamExecutor(final QueryExecutor<T> executor) {
    if (executor == null) {
      throw new IllegalArgumentException("Executor cnnot be null.");
    }
    if (downstream_executors == null) {
      downstream_executors = Lists.<QueryExecutor<T>>newArrayList(executor);
    } else {
      downstream_executors.add(executor);
    }
  }
  
  @VisibleForTesting
  Set<QueryExecution<T>> outstandingRequests() {
    return outstanding_executions;
  }

  @VisibleForTesting 
  List<QueryExecutor<T>> downstreamExecutors() {
    return downstream_executors;
  }
}