/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2015  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.core.algorithm.invariants;

import static com.google.common.base.Preconditions.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sosy_lab.common.LazyFutureTask;
import org.sosy_lab.common.Pair;
import org.sosy_lab.common.concurrency.Threads;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.time.Timer;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.ShutdownNotifier;
import org.sosy_lab.cpachecker.core.ShutdownNotifier.ShutdownRequestListener;
import org.sosy_lab.cpachecker.core.algorithm.bmc.BMCAlgorithm;
import org.sosy_lab.cpachecker.core.defaults.SingletonPrecision;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.AbstractStateWithLocation;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.FormulaReportingState;
import org.sosy_lab.cpachecker.core.interfaces.Partitionable;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.StateSpacePartition;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSetFactory;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateCPA;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;
import org.sosy_lab.cpachecker.util.CFAUtils;
import org.sosy_lab.cpachecker.util.CPAs;
import org.sosy_lab.cpachecker.util.predicates.interfaces.BooleanFormula;
import org.sosy_lab.cpachecker.util.predicates.interfaces.PathFormulaManager;
import org.sosy_lab.cpachecker.util.predicates.interfaces.view.FormulaManagerView;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;

/**
 * Generate invariants using k-induction.
 */
public class KInductionInvariantGenerator implements InvariantGenerator {

  private final BMCAlgorithm bmcAlgorithm;

  private final ConfigurableProgramAnalysis cpa;

  private final ReachedSetFactory reachedSetFactory;

  private final LogManager logger;

  private final ShutdownNotifier shutdownNotifier;

  private boolean async = true;

  private Future<UnmodifiableReachedSet> invariantGenerationFuture = null;

  private final Timer invariantGeneration = new Timer();

  private final List<UpdateListener> updateListeners = new CopyOnWriteArrayList<>();

  private final CFA cfa;

  private final PathFormulaManager clientPFM;

  private ReachedSet currentResults;

  private final ExecutorService executorService = Executors.newSingleThreadExecutor(Threads.threadFactory());

  private final ShutdownRequestListener shutdownListener;

  private final AtomicBoolean areNewInvariantsAvailable = new AtomicBoolean(true);

  public KInductionInvariantGenerator(
      BMCAlgorithm pBMCAlgorithm,
      ReachedSetFactory pReachedSetFactory,
      ConfigurableProgramAnalysis pCPA,
      LogManager pLogger,
      ShutdownNotifier pShutdownNotifier,
      CFA pCFA,
      PathFormulaManager pClientPFM,
      boolean pAsync) throws InvalidConfigurationException {
    Preconditions.checkNotNull(pBMCAlgorithm);
    PredicateCPA predicateCPA = CPAs.retrieveCPA(pCPA, PredicateCPA.class);
    if (predicateCPA == null) {
      throw new InvalidConfigurationException("Predicate CPA required");
    }
    if (async && !predicateCPA.getSolver().getFormulaManager().getVersion().toLowerCase().contains("smtinterpol")) {
      throw new InvalidConfigurationException("Solver does not support concurrent execution, use SMTInterpol instead.");
    }
    bmcAlgorithm = pBMCAlgorithm;
    cpa = pCPA;
    reachedSetFactory = pReachedSetFactory;
    logger = pLogger;
    shutdownNotifier = pShutdownNotifier;
    cfa = pCFA;
    clientPFM = pClientPFM;
    currentResults = reachedSetFactory.create();

    shutdownListener = new ShutdownRequestListener() {

      @Override
      public void shutdownRequested(String pReason) {
        executorService.shutdownNow();
      }
    };

    bmcAlgorithm.addUpdateListener(new UpdateListener() {

      @Override
      public void updated() {
        areNewInvariantsAvailable.set(true);
      }
    });
  }

  @Override
  public void start(final CFANode pInitialLocation) {

    checkNotNull(pInitialLocation);
    checkState(invariantGenerationFuture == null);


    Callable<UnmodifiableReachedSet> task = new Callable<UnmodifiableReachedSet>() {

      @Override
      public UnmodifiableReachedSet call() throws InterruptedException, CPAException {
        invariantGeneration.start();
        shutdownNotifier.shutdownIfNecessary();

        try {
          ReachedSet reachedSet = reachedSetFactory.create();
          AbstractState initialState = cpa.getInitialState(pInitialLocation, StateSpacePartition.getDefaultPartition());
          Precision initialPrecision = cpa.getInitialPrecision(pInitialLocation, StateSpacePartition.getDefaultPartition());
          reachedSet.add(initialState, initialPrecision);
          bmcAlgorithm.run(reachedSet);
          return getResults();

        } finally {
          CPAs.closeCpaIfPossible(cpa, logger);
          CPAs.closeIfPossible(bmcAlgorithm, logger);
          invariantGeneration.stop();
        }
      }

    };

    if (async) {
      shutdownNotifier.registerAndCheckImmediately(shutdownListener);
      if (!executorService.isShutdown() && !shutdownNotifier.shouldShutdown()) {
        invariantGenerationFuture = executorService.submit(task);
      } else {
        invariantGenerationFuture = new LazyFutureTask<>(task);
      }
    } else {
      invariantGenerationFuture = new LazyFutureTask<>(task);
    }
  }

  private UnmodifiableReachedSet getResults() throws CPATransferException, InterruptedException {
    if (areNewInvariantsAvailable.getAndSet(false)) {
      ReachedSet currentResults = reachedSetFactory.create();
      currentResults.addAll(FluentIterable.from(cfa.getAllNodes()).transform(new Function<CFANode, Pair<AbstractState, Precision>>() {

        @Override
        public Pair<AbstractState, Precision> apply(CFANode pArg0) {
          return Pair.<AbstractState, Precision>of(new ResultState(pArg0), SingletonPrecision.getInstance());
        }}));
      this.currentResults = currentResults;
    }
    return currentResults;
  }

  @Override
  public void cancel() {
    checkState(invariantGenerationFuture != null);
    shutdownNotifier.requestShutdown("Invariant generation cancel requested.");
  }

  @Override
  public UnmodifiableReachedSet get() throws CPAException, InterruptedException {
    if (async) {
      return getResults();
    } else {
      try {
        return invariantGenerationFuture.get();
      } catch (ExecutionException e) {
        return reachedSetFactory.create();
      }
    }
  }

  @Override
  public Timer getTimeOfExecution() {
    return invariantGeneration;
  }

  @Override
  public void addUpdateListener(UpdateListener pUpdateListener) {
    Preconditions.checkNotNull(pUpdateListener);
    updateListeners.add(pUpdateListener);
  }

  @Override
  public void removeUpdateListener(UpdateListener pUpdateListener) {
    Preconditions.checkNotNull(pUpdateListener);
    updateListeners.remove(pUpdateListener);
  }

  private class ResultState implements FormulaReportingState, AbstractStateWithLocation, Partitionable {

    private final CFANode location;

    public ResultState(CFANode pLocation) {
      location = pLocation;
    }

    @Override
    public Iterable<CFAEdge> getOutgoingEdges() {
      return CFAUtils.leavingEdges(getLocationNode());
    }

    @Override
    public CFANode getLocationNode() {
      return location;
    }

    @Override
    public BooleanFormula getFormulaApproximation(FormulaManagerView pManager) {
      return bmcAlgorithm.getCurrentLocationInvariants(location, pManager, clientPFM);
    }

    @Override
    public Object getPartitionKey() {
      return getLocationNode();
    }

  }

}