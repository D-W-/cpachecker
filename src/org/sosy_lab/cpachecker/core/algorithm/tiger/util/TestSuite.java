/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
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
package org.sosy_lab.cpachecker.core.algorithm.tiger.util;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.c.CLabelNode;
import org.sosy_lab.cpachecker.core.algorithm.tiger.goals.Goal;
import org.sosy_lab.cpachecker.core.algorithm.tiger.goals.clustering.InfeasibilityPropagation.Prediction;
import org.sosy_lab.cpachecker.util.Pair;
import org.sosy_lab.cpachecker.util.predicates.regions.NamedRegionManager;
import org.sosy_lab.cpachecker.util.predicates.regions.Region;

public class TestSuite {

  private Map<TestCase, List<Goal>> mapping;
  private Map<Goal, List<TestCase>> coveringTestCases;
  private Map<Pair<TestCase, Goal>, Region> coveringPresenceConditions;
  private Set<Goal> testGoals;
  private Set<Goal> infeasibleGoals;
  private Map<Integer, Pair<Goal, Region>> timedOutGoals;
  private int numberOfFeasibleGoals = 0;
  private NamedRegionManager bddCpaNamedRegionManager;
  private boolean printLabels;
  boolean useTigerAlgorithm_with_pc;
  private long generationStartTime = 0;
  private Map<Goal, Region> remainingPresenceConditions;
  private Map<Goal, Region> infeasiblePresenceConditions;

  public TestSuite(NamedRegionManager pBddCpaNamedRegionManager, boolean pPrintLabels,
      boolean pUseTigerAlgorithm_with_pc) {
    mapping = new HashMap<>();
    coveringTestCases = new HashMap<>();
    coveringPresenceConditions = new HashMap<>();
    testGoals = new HashSet<>();
    infeasibleGoals = new HashSet<>();
    timedOutGoals = new HashMap<>();
    bddCpaNamedRegionManager = pBddCpaNamedRegionManager;
    printLabels = pPrintLabels;
    useTigerAlgorithm_with_pc = pUseTigerAlgorithm_with_pc;
    remainingPresenceConditions = new HashMap<>();
    infeasiblePresenceConditions = new HashMap<>();
  }

  public Set<Goal> getGoals() {
    return testGoals;
  }

  public int getNumberOfFeasibleTestGoals() {
    return numberOfFeasibleGoals;
  }

  public int getNumberOfInfeasibleTestGoals() {
    return infeasibleGoals.size();
  }

  public int getNumberOfTimedoutTestGoals() {
    return timedOutGoals.size();
  }

  public boolean hasTimedoutTestGoals() {
    return !timedOutGoals.isEmpty();
  }

  public void addTimedOutGoal(Goal goal, Region presenceCondition) {
    timedOutGoals.put(goal.getIndex(), Pair.of(goal, presenceCondition));
  }

  public Map<Integer, Pair<Goal, Region>> getTimedOutGoals() {
    return timedOutGoals;
  }

  public Region getRemainingPresenceCondition(Goal pGoal) {
    return remainingPresenceConditions.get(pGoal);
  }

  public void setRemainingPresenceCondition(Goal pGoal, Region presenceCondtion) {
    remainingPresenceConditions.put(pGoal, presenceCondtion);
  }

  public Region getInfeasiblePresenceCondition(Goal pGoal) {
    return infeasiblePresenceConditions.get(pGoal);
  }

  public void setInfeasiblePresenceCondition(Goal pGoal, Region presenceCondtion) {
    infeasiblePresenceConditions.put(pGoal, presenceCondtion);
  }

  public List<TestCase> getCoveringTestCases(Goal pGoal) {
    return coveringTestCases.get(pGoal);
  }

  public boolean isGoalCoveredByTestCase(Goal pGoal, TestCase pTestCase) {
    return coveringTestCases.get(pGoal).contains(pTestCase);
  }

  public void addInfeasibleGoal(Goal goal, Region presenceCondition, Prediction[] pGoalPrediction) {
    if (useTigerAlgorithm_with_pc) {
      if (presenceCondition != null && infeasibleGoals.contains(goal)) {
        setInfeasiblePresenceCondition(goal,
            bddCpaNamedRegionManager.makeOr(infeasiblePresenceConditions.get(goal), presenceCondition));
      } else {
        setInfeasiblePresenceCondition(goal, presenceCondition);
      }
    }

    if (!infeasibleGoals.contains(goal)) {
      infeasibleGoals.add(goal);
    }

    if (pGoalPrediction != null) {
      pGoalPrediction[goal.getIndex() - 1] = Prediction.INFEASIBLE;
    }
  }

  public boolean addTestCase(TestCase testcase, Goal goal, Region pPresenceCondition) {
    if (testSuiteAlreadyContrainsTestCase(testcase, goal)) { return true; }
    numberOfFeasibleGoals++;

    List<Goal> goals = mapping.get(testcase);
    List<TestCase> testcases = coveringTestCases.get(goal);

    boolean testcaseExisted = true;

    if (goals == null) {
      goals = new LinkedList<>();
      mapping.put(testcase, goals);
      testcaseExisted = false;
    }

    if (testcases == null) {
      testcases = new LinkedList<>();
      coveringTestCases.put(goal, testcases);
    }

    goals.add(goal);
    testcases.add(testcase);

    if (useTigerAlgorithm_with_pc) {
      coveringPresenceConditions.put(Pair.of(testcase, goal), pPresenceCondition);

      setRemainingPresenceCondition(goal, bddCpaNamedRegionManager.makeAnd(getRemainingPresenceCondition(goal),
          bddCpaNamedRegionManager.makeNot(pPresenceCondition)));
    }

    return testcaseExisted;
  }

  private boolean testSuiteAlreadyContrainsTestCase(TestCase pTestcase, Goal pGoal) {
    List<Goal> goals = mapping.get(pTestcase);
    if (goals != null) { return goals.contains(pGoal); }

    return false;
  }

  public Set<TestCase> getTestCases() {
    return mapping.keySet();
  }

  public int getNumberOfTestCases() {
    return getTestCases().size();
  }


  public Set<Goal> getInfeasibleGoals() {
    return infeasibleGoals;
  }

  public List<Goal> getTestGoalsCoveredByTestCase(TestCase testcase) {
    return mapping.get(testcase);
  }

  public long getGenerationStartTime() {
    return generationStartTime;
  }

  public void setGenerationStartTime(long pGenerationStartTime) {
    generationStartTime = pGenerationStartTime;
  }

  @Override
  public String toString() {
    StringBuffer str = new StringBuffer();

    for (Map.Entry<TestCase, List<Goal>> entry : mapping.entrySet()) {
      str.append(entry.getKey().toString() + "\n");
      List<CFAEdge> errorPath = entry.getKey().getErrorPath();
      if (entry.getKey().getGenerationTime() != -1) {
        str.append("Generation Time: " + (entry.getKey().getGenerationTime() - getGenerationStartTime()) + "\n");
      }
      if (errorPath != null) {
        str.append("Errorpath Length: " + entry.getKey().getErrorPath().size() + "\n");
      }

      for (Goal goal : entry.getValue()) {
        str.append("Goal ");
        str.append(getTestGoalLabel(goal));

        Region presenceCondition = coveringPresenceConditions.get(Pair.of(entry.getKey(), goal));
        if (presenceCondition != null) {
          str.append(": " + bddCpaNamedRegionManager.dumpRegion(presenceCondition).toString().replace("@0", "")
              .replace(" & TRUE", ""));
        }
        str.append("\n");
      }

      if (printLabels) {
        str.append("Labels: ");
        for (CFAEdge edge : entry.getKey().getErrorPath()) {
          if (edge == null) {
            continue;
          }
          CFANode predecessor = edge.getPredecessor();
          if (predecessor instanceof CLabelNode && !((CLabelNode) predecessor).getLabel().isEmpty()) {
            str.append(((CLabelNode) predecessor).getLabel());
            str.append(" ");
          }
        }
        str.append("\n");
      }

      str.append("\n");
    }

    if (!infeasibleGoals.isEmpty()) {
      str.append("infeasible:\n");

      for (Goal entry : infeasibleGoals) {
        str.append("Goal ");
        str.append(getTestGoalLabel(entry));

        Region presenceCondition = infeasiblePresenceConditions.get(entry);
        if (presenceCondition != null) {
          str.append(": cannot be covered with PC ");
          str.append(bddCpaNamedRegionManager.dumpRegion(presenceCondition).toString().replace("@0", "")
              .replace(" & TRUE", ""));
        }
        str.append("\n");
      }

      str.append("\n");
    }

    if (!timedOutGoals.isEmpty()) {
      str.append("timed out:\n");

      for (Entry<Integer, Pair<Goal, Region>> entry : timedOutGoals.entrySet()) {
        str.append("Goal ");
        str.append(getTestGoalLabel(entry.getValue().getFirst()));

        Region presenceCondition = entry.getValue().getSecond();
        if (presenceCondition != null) {
          str.append(": timed out for PC ");
          str.append(bddCpaNamedRegionManager.dumpRegion(presenceCondition));
        }
        str.append("\n");
      }

      str.append("\n");
    }

    return str.toString().replace("__SELECTED_FEATURE_", "");
  }

  /**
   * Returns the label of a test goal if there is one; otherwise the goal index will be returned.
   *
   * @param goal
   * @return
   */
  public String getTestGoalLabel(Goal goal) {
    String label = "";

    CFANode predecessor = goal.getCriticalEdge().getPredecessor();
    if (predecessor instanceof CLabelNode && !((CLabelNode) predecessor).getLabel().isEmpty()) {
      label = ((CLabelNode) predecessor).getLabel();
    } else {
      label = new Integer(goal.getIndex()).toString();
    }

    return label;
  }

  /**
   * Summarizes the presence conditions of tests in this testsuite that cover the parameter test goal.
   */
  public Region getGoalCoverage(Goal pGoal) {
    Region totalCoverage = bddCpaNamedRegionManager.makeFalse();
    for (Entry<TestCase, List<Goal>> entry : this.mapping.entrySet()) {
      if (entry.getValue().contains(pGoal)) {
        assert entry.getKey().getPresenceCondition() != null;
        totalCoverage = bddCpaNamedRegionManager.makeOr(totalCoverage, entry.getKey().getPresenceCondition());
      }
    }
    return totalCoverage;
  }

  public boolean isGoalInfeasible(Goal goal) {
    return infeasibleGoals.contains(goal);
  }

  public void addGoals(Collection<Goal> pGoals) {
    if (useTigerAlgorithm_with_pc) {
      for (Goal goal : pGoals) {
        remainingPresenceConditions.put(goal, bddCpaNamedRegionManager.makeTrue());
      }
    }
    testGoals.addAll(pGoals);
  }

  public boolean isGoalCovered(Goal pGoal) {
    if (useTigerAlgorithm_with_pc) {
      return remainingPresenceConditions.get(pGoal).isFalse();
    } else {
      List<TestCase> testCases = coveringTestCases.get(pGoal);
      return (testCases != null && testCases.size() > 0);
    }
  }

  public boolean areGoalsCovered(Set<Goal> pTestGoalsToBeProcessed) {
    for (Goal goal : pTestGoalsToBeProcessed) {
      if (!isGoalCovered(goal)) {
        return false;
      }
    }

    return true;
  }

  public boolean areGoalsInfeasible(Set<Goal> pTestGoalsToBeProcessed) {
    for (Goal goal : pTestGoalsToBeProcessed) {
      if (!isGoalInfeasible(goal)) {
        return false;
      }
    }
    return true;
  }

  public void addTimedOutGoals(Set<Goal> pTestGoalsToBeProcessed) {
    for (Goal goal : pTestGoalsToBeProcessed) {
      addTimedOutGoal(goal, remainingPresenceConditions.get(goal));
    }
  }

  public boolean areGoalsCoveredOrInfeasible(Set<Goal> pGoals) {
    for (Goal goal : pGoals) {
      if (!(isGoalCovered(goal) || isGoalInfeasible(goal))) {
        return false;
      }
    }
    return true;
  }

  public boolean isGoalCoveredOrInfeasible(Goal pGoal) {
    return isGoalCovered(pGoal) || isGoalInfeasible(pGoal);
  }

  public boolean isGoalTimedout(Goal pGoal) {
    return timedOutGoals.containsKey(pGoal);
  }

}