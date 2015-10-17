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
package org.sosy_lab.cpachecker.cpa.automaton;

import java.util.Set;

import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.PrecisionAdjustment;
import org.sosy_lab.cpachecker.core.interfaces.PrecisionAdjustmentResult;
import org.sosy_lab.cpachecker.core.interfaces.PrecisionAdjustmentResult.Action;
import org.sosy_lab.cpachecker.core.interfaces.Property;
import org.sosy_lab.cpachecker.core.interfaces.Targetable;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.cpa.arg.ARGCPA;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.CPAs;
import org.sosy_lab.cpachecker.util.globalinfo.GlobalInfo;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Multiset;

@Options(prefix="cpa.automaton.prec")
public class ControlAutomatonPrecisionAdjustment implements PrecisionAdjustment {

  private final AutomatonState bottomState;
  private final AutomatonState inactiveState;

  @Option(secure=true, description="Handle at most k (> 0) violation of one property.")
  private int targetHandledAfterViolations = 1;

  @Option(secure=true, description="Disable a property after a specific number of refinements has been performed for it.")
  private int targetDisabledAfterRefinements = 0;

  @Option(secure=true, description="Change the precision locally.")
  private boolean localPrecisionUpdate = false;

  enum TargetStateVisitBehaviour {
    SIGNAL, // Signal the target state (default)
    BOTTOM, // Change to the automata state BOTTOM (when splitting states on a violation)
    INACTIVE, // Change to the automata state INACTIVE (when NOT splitting states on a violation)
  }
  @Option(secure=true, description="Behaviour on a property that has already been fully handled.")
  private TargetStateVisitBehaviour onHandledTarget = TargetStateVisitBehaviour.SIGNAL;

  public ControlAutomatonPrecisionAdjustment(
      Configuration pConfig,
      AutomatonState pTopState,
      AutomatonState pBottomState,
      AutomatonState pInactiveState)
          throws InvalidConfigurationException {

    pConfig.inject(this);

    this.bottomState = pBottomState;
    this.inactiveState = pInactiveState;
  }

  private int maxInfeasibleCexFor(Set<? extends Property> pProperties) {
    ARGCPA argCpa = CPAs.retrieveCPA(GlobalInfo.getInstance().getCPA().get(), ARGCPA.class);
    return argCpa.getCexSummary().getMaxInfeasibleCexCountFor(pProperties);
  }

  private void signalDisablingProperties(Set<? extends Property> pProperty) {
    ARGCPA argCpa = CPAs.retrieveCPA(GlobalInfo.getInstance().getCPA().get(), ARGCPA.class);
    for (Property p: pProperty) {
      argCpa.getCexSummary().signalPropertyDisabled(p);
    }
  }

  private int timesEqualTargetInReached(UnmodifiableReachedSet pStates, AbstractState pFullState,
      Set<? extends Property> pProperties) {

    assert !(pFullState instanceof AutomatonState);
    assert pFullState instanceof Targetable;
    assert ((Targetable) pFullState).isTarget();

    ARGCPA argCpa = CPAs.retrieveCPA(GlobalInfo.getInstance().getCPA().get(), ARGCPA.class);
    Multiset<Property> reachedViolations = argCpa.getCexSummary().getFeasiblePropertyViolations();

    int result = Integer.MAX_VALUE;
    for (Property p: pProperties) {
      result = Math.min(result, reachedViolations.count(p));
    }

    return result;
  }

  @Override
  public Optional<PrecisionAdjustmentResult> prec(
      AbstractState pState,
      Precision pPrecision,
      UnmodifiableReachedSet pStates,
      Function<AbstractState, AbstractState> pStateProjection,
      AbstractState pFullState)
    throws CPAException, InterruptedException {

    // Casts to the AutomataCPA-specific types
    final AutomatonPrecision pi = (AutomatonPrecision) pPrecision;
    final AutomatonInternalState internalState = ((AutomatonState) pState).getInternalState();
    final AutomatonState state = (AutomatonState) pState;

    // Specific handling of potential target states!!!
    if (state.isTarget()) {

      final AutomatonState stateOnHandledTarget;
      switch (onHandledTarget) {
        case BOTTOM: stateOnHandledTarget = bottomState; break;
        case INACTIVE: stateOnHandledTarget = inactiveState; break;
        default: stateOnHandledTarget = state;
      }

      // A property might have already been disabled!
      //    Handling of blacklisted (disabled) states:
      if (pi.getBlacklist().containsAll(state.getViolatedProperties())) {
        return Optional.of(PrecisionAdjustmentResult.create(
            stateOnHandledTarget,
            pi, Action.CONTINUE));
      }

      // Handle a target state
      //    We might disable certain target states
      //      (they should not be considered as target states)
      if (localPrecisionUpdate
          && onHandledTarget != TargetStateVisitBehaviour.SIGNAL) {
        assert targetHandledAfterViolations > 0 || targetDisabledAfterRefinements > 0;

        Set<AutomatonSafetyProperty> properties = AbstractStates.extractViolatedProperties(state, AutomatonSafetyProperty.class);
        int timesHandled = timesEqualTargetInReached(pStates, pFullState, properties);
        int maxInfeasibleCexs = maxInfeasibleCexFor(properties);

        final boolean disable = (timesHandled >= targetHandledAfterViolations) // the new state is the ith+1
                 || (targetDisabledAfterRefinements > 0
                     && maxInfeasibleCexs > targetDisabledAfterRefinements);

        if (disable) {
          final AutomatonPrecision piPrime = pi.cloneAndAddBlacklisted(properties);
          signalDisablingProperties(properties);

          return Optional.of(PrecisionAdjustmentResult.create(
              stateOnHandledTarget,
              piPrime, Action.CONTINUE));
        }
      }
    }

    // Handle the BREAK state
    if (internalState.getName().equals(AutomatonInternalState.BREAK.getName())) {
      return Optional.of(PrecisionAdjustmentResult.create(pState, pPrecision, Action.BREAK));
    }

    // No precision adjustment
    return Optional.of(PrecisionAdjustmentResult.create(pState, pPrecision, Action.CONTINUE));
  }

}
