/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2016  Dirk Beyer
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
package org.sosy_lab.cpachecker.cpa.smg.objects.dls;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.sosy_lab.cpachecker.cpa.smg.SMGAbstractionCandidate;
import org.sosy_lab.cpachecker.cpa.smg.SMGAbstractionFinder;
import org.sosy_lab.cpachecker.cpa.smg.SMGEdgeHasValue;
import org.sosy_lab.cpachecker.cpa.smg.SMGEdgeHasValueFilter;
import org.sosy_lab.cpachecker.cpa.smg.SMGEdgePointsTo;
import org.sosy_lab.cpachecker.cpa.smg.SMGInconsistentException;
import org.sosy_lab.cpachecker.cpa.smg.SMGTargetSpecifier;
import org.sosy_lab.cpachecker.cpa.smg.SMGUtils;
import org.sosy_lab.cpachecker.cpa.smg.graphs.CLangSMG;
import org.sosy_lab.cpachecker.cpa.smg.join.SMGJoinSubSMGsForAbstraction;
import org.sosy_lab.cpachecker.cpa.smg.objects.SMGObject;
import org.sosy_lab.cpachecker.cpa.smg.objects.SMGRegion;
import org.sosy_lab.cpachecker.util.Pair;

public class SMGDoublyLinkedListCandidateFinder implements SMGAbstractionFinder {

  private CLangSMG smg;
  private  Map<SMGObject, Map<Pair<Integer, Integer>, SMGDoublyLinkedListCandidate>> candidates = new HashMap<>();
  private  Map<SMGDoublyLinkedListCandidate, Integer> candidateLength = new HashMap<>();

  private final int seqLengthThreshold;

  public SMGDoublyLinkedListCandidateFinder() {
    seqLengthThreshold = 1;
  }

  public SMGDoublyLinkedListCandidateFinder(int pSeqLengthThreshold) {
    seqLengthThreshold = pSeqLengthThreshold;
  }

  public Map<SMGDoublyLinkedListCandidate, Integer> getCandidateLength() {
    return candidateLength;
  }

  @Override
  public Set<SMGAbstractionCandidate> traverse(CLangSMG pSmg) throws SMGInconsistentException {
    smg = pSmg;

    candidateLength.clear();
    candidates.clear();

    for (SMGObject object : smg.getHeapObjects()) {
      startTraversal(object);
    }

    Set<SMGAbstractionCandidate> returnSet = new HashSet<>();
    for (Map<Pair<Integer, Integer>, SMGDoublyLinkedListCandidate> objCandidates : candidates
        .values()) {
      for (SMGDoublyLinkedListCandidate candidate : objCandidates.values()) {
        if (candidateLength.get(candidate) >= seqLengthThreshold) {
          returnSet.add(new SMGDoublyLinkedListCandidateSequence(candidate, candidateLength.get(candidate)));
        }
      }
    }
    return Collections.unmodifiableSet(returnSet);
  }

  private void startTraversal(SMGObject pObject) throws SMGInconsistentException {
    if (candidates.containsKey(pObject)) {
      // Processed already in continueTraversal
      return;
    }
    candidates.put(pObject, new HashMap<Pair<Integer, Integer>, SMGDoublyLinkedListCandidate>());
    createCandidatesOfObject(pObject);
  }

  private void createCandidatesOfObject(SMGObject pObject) throws SMGInconsistentException {

    if (!smg.isObjectValid(pObject) || !(pObject.getLevel() == 0)) {
      return;
    }

    Set<SMGEdgeHasValue> hvesOfObject = smg.getHVEdges(SMGEdgeHasValueFilter.objectFilter(pObject));

    if (hvesOfObject.size() < 2) {
      return;
    }

    for (SMGEdgeHasValue hveNext : hvesOfObject) {

      int nfo = hveNext.getOffset();
      int nextPointer = hveNext.getValue();

      if (!smg.isPointer(nextPointer)) {
        continue;
      }

      SMGEdgePointsTo nextPointerEdge = smg.getPointer(nextPointer);
      int hfo = nextPointerEdge.getOffset();
      SMGTargetSpecifier nextPointerTg = nextPointerEdge.getTargetSpecifier();

      if (!(nextPointerTg == SMGTargetSpecifier.REGION
          || nextPointerTg == SMGTargetSpecifier.FIRST)) {
        continue;
      }

      SMGObject nextObject = nextPointerEdge.getObject();

      Set<SMGEdgeHasValue> nextObjectHves = smg.getHVEdges(SMGEdgeHasValueFilter.objectFilter(nextObject));

      if (nextObjectHves.size() < 2) {
        continue;
      }

      for (SMGEdgeHasValue hvePrev : nextObjectHves) {

        int pfo = hvePrev.getOffset();
        int prevPointer = hvePrev.getValue();

        if(!(nfo < pfo)) {
          continue;
        }

        if (!smg.isPointer(prevPointer)) {
          continue;
        }

        SMGEdgePointsTo prevPointerEdge = smg.getPointer(prevPointer);

        if(prevPointerEdge.getOffset() != hfo) {
          continue;
        }

        SMGTargetSpecifier prevPointerTg = prevPointerEdge.getTargetSpecifier();

        if (!(prevPointerTg == SMGTargetSpecifier.REGION
            || prevPointerTg == SMGTargetSpecifier.LAST)) {
          continue;
        }

        if(pObject != prevPointerEdge.getObject()) {
          continue;
        }

        SMGDoublyLinkedListCandidate candidate =
            new SMGDoublyLinkedListCandidate(pObject, hfo, pfo, nfo);
        candidates.get(pObject).put(Pair.of(nfo, pfo), candidate);
        candidateLength.put(candidate, 0);
        continueTraversal(nextPointer, candidate);
      }
    }
  }


  private void continueTraversal(int pValue, SMGDoublyLinkedListCandidate pPrevCandidate) throws SMGInconsistentException {

    // the next object is doubly linked with the prev object, which was checked in start traversel.
    SMGEdgePointsTo pt = smg.getPointer(pValue);
    SMGObject nextObject = pt.getObject();
    SMGObject startObject = pPrevCandidate.getObject();

    // First, calculate the longest mergeable sequence of the next object
    if(!smg.isHeapObject(nextObject)) {
      return;
    }

    if (! candidates.containsKey(nextObject)) {
      startTraversal(nextObject);
    }

    Map<Pair<Integer, Integer>, SMGDoublyLinkedListCandidate> objectCandidates = candidates.get(nextObject);
    Integer nfo = pPrevCandidate.getNfo();
    Integer pfo = pPrevCandidate.getPfo();

    if (! objectCandidates.containsKey(Pair.of(nfo, pfo))) {
      return;
    }

    SMGDoublyLinkedListCandidate candidate = objectCandidates.get(Pair.of(nfo, pfo));

    // Second, find out if the subsmgs are mergeable
    SMGJoinSubSMGsForAbstraction join = new SMGJoinSubSMGsForAbstraction(new CLangSMG(smg), startObject, nextObject, candidate);

    if(!join.isDefined()) {
      return;
    }

    Set<SMGObject> nonSharedObject1 = join.getNonSharedObjectsFromSMG1();
    Set<Integer> nonSharedValues1 = join.getNonSharedValuesFromSMG1();
    Set<SMGObject> nonSharedObject2 = join.getNonSharedObjectsFromSMG2();
    Set<Integer> nonSharedValues2 = join.getNonSharedValuesFromSMG2();

    Set<SMGObject> objectsOfSubSmg1 = new HashSet<>();
    Set<SMGObject> objectsOfSubSmg2 = new HashSet<>();
    Set<Integer> valuesOfSubSmg1 = new HashSet<>();
    Set<Integer> valuesOfSubSmg2 = new HashSet<>();

    getSubSmgOf(startObject, nfo, pfo, smg, valuesOfSubSmg1, objectsOfSubSmg1);
    getSubSmgOf(nextObject, nfo, pfo, smg, valuesOfSubSmg2, objectsOfSubSmg2);

    objectsOfSubSmg1.remove(startObject);
    objectsOfSubSmg2.remove(nextObject);

    // Third, calculate if the respective nfo,pfo restricted subsmgs are only reachable from their candidate objects
    if (!isSubSmgSeperate(nonSharedObject1, nonSharedValues1, smg, objectsOfSubSmg1,
        valuesOfSubSmg1)) {
      return;
    }

    if (!isSubSmgSeperate(nonSharedObject2, nonSharedValues2, smg, objectsOfSubSmg2,
        valuesOfSubSmg2)) {
      return;
    }

    // check if the sequence is uninterrupted
    Set<SMGEdgePointsTo> ptes1 = SMGUtils.getPointerToThisObject(startObject, smg);
    Set<SMGEdgePointsTo> ptes2 = SMGUtils.getPointerToThisObject(nextObject, smg);


    for (SMGEdgePointsTo pte : ptes1) {
      if (pte.getOffset() != candidate.getHfo()) {
        if (!nonSharedValues1.contains(pte.getValue())) {
          return;
        }
      } else if (startObject instanceof SMGDoublyLinkedList
          && pte.getTargetSpecifier() == SMGTargetSpecifier.LAST) {
        Set<SMGEdgeHasValue> prevs = smg.getHVEdges(SMGEdgeHasValueFilter.valueFilter(pte.getValue()));

        if(prevs.size() != 1) {
          return;
        }
      }
    }

    for (SMGEdgePointsTo pte : ptes2) {
      if (pte.getOffset() != candidate.getHfo()) {
        if (!nonSharedValues2.contains(pte.getValue())) {
          return;
        }
      } else if (nextObject instanceof SMGDoublyLinkedList
          && pte.getTargetSpecifier() == SMGTargetSpecifier.FIRST) {
        Set<SMGEdgeHasValue> prevs =
            smg.getHVEdges(SMGEdgeHasValueFilter.valueFilter(pte.getValue()));

        if (prevs.size() != 1) {
          return;
        }
      } else if (nextObject instanceof SMGRegion) {
        if (candidateLength.get(candidate) != 0) {
          Set<SMGEdgeHasValue> hves =
              smg.getHVEdges(SMGEdgeHasValueFilter.valueFilter(pte.getValue()));

          /* We already established a doubly linked relation with a predecessor and successor,
             Therefore there may be only these two edges, and edges from the subsmg.*/
          int count = 0;
          for (SMGEdgeHasValue hve : hves) {
            if (!nonSharedObject2.contains(hve.getObject())) {
              count = count + 1;
            }
          }

          if(count != 2) {
            return;
          }
        }
      }
    }

    candidateLength.put(pPrevCandidate, candidateLength.get(candidate) + 1);
  }

  private boolean isSubSmgSeperate(Set<SMGObject> nonSharedObject, Set<Integer> nonSharedValues,
      CLangSMG smg, Set<SMGObject> reachableObjects, Set<Integer> reachableValues) {

    for (SMGObject obj : nonSharedObject) {
      if (!smg.isHeapObject(obj)) {
        return false;
      }

      Set<SMGEdgePointsTo> pointer = SMGUtils.getPointerToThisObject(obj, smg);

      for (SMGEdgePointsTo pte : pointer) {
        if (!reachableValues.contains(pte.getValue())) {
          return false;
        }
      }
    }

    for (Integer val : nonSharedValues) {
      Set<SMGEdgeHasValue> hves =
          smg.getHVEdges(new SMGEdgeHasValueFilter().filterHavingValue(val));

      for (SMGEdgeHasValue hve : hves) {
        if (!reachableObjects.contains(hve.getObject())) {
          return false;
        }
      }
    }

    return true;
  }

  private void getSubSmgOf(SMGObject pObject, int nfo, int pfo, CLangSMG inputSmg,
      Set<Integer> pValues, Set<SMGObject> pObjects) {

    Set<SMGObject> toBeChecked = new HashSet<>();

    pObjects.add(pObject);

    Set<SMGEdgeHasValue> hves = inputSmg.getHVEdges(SMGEdgeHasValueFilter.objectFilter(pObject));

    for (SMGEdgeHasValue hve : hves) {

      if (hve.getOffset() != pfo && hve.getOffset() != nfo) {

        int subSmgValue = hve.getValue();

        if (!pValues.contains(subSmgValue)) {
          pValues.add(subSmgValue);
        }

        if (inputSmg.isPointer(subSmgValue)) {
          SMGEdgePointsTo reachedObjectSubSmgPTEdge = inputSmg.getPointer(subSmgValue);
          SMGObject reachedObjectSubSmg = reachedObjectSubSmgPTEdge.getObject();

          if (!pObjects.contains(reachedObjectSubSmg)) {
            pObjects.add(reachedObjectSubSmg);
            toBeChecked.add(reachedObjectSubSmg);
          }
        }
      }
    }

    Set<SMGObject> toCheck = new HashSet<>();

    while (!toBeChecked.isEmpty()) {
      toCheck.clear();
      toCheck.addAll(toBeChecked);
      toBeChecked.clear();

      for (SMGObject objToCheck : toCheck) {
        getSubSmgOf(objToCheck, toBeChecked, inputSmg, pObjects, pValues);
      }
    }
  }

  private void getSubSmgOf(SMGObject pObjToCheck,
      Set<SMGObject> pToBeChecked,  CLangSMG pInputSmg, Set<SMGObject> pObjects, Set<Integer> pValues) {

    Set<SMGEdgeHasValue> hves = pInputSmg.getHVEdges(SMGEdgeHasValueFilter.objectFilter(pObjToCheck));

    for (SMGEdgeHasValue hve : hves) {

      int subDlsValue = hve.getValue();

      if (!pValues.contains(subDlsValue)) {
        pValues.add(subDlsValue);
      }

      if (pInputSmg.isPointer(subDlsValue)) {
        SMGEdgePointsTo reachedObjectSubSmgPTEdge = pInputSmg.getPointer(subDlsValue);
        SMGObject reachedObjectSubSmg = reachedObjectSubSmgPTEdge.getObject();

        if ((!pObjects.contains(reachedObjectSubSmg))) {
          pObjects.add(reachedObjectSubSmg);
          pToBeChecked.add(reachedObjectSubSmg);
        }
      }
    }
  }
}