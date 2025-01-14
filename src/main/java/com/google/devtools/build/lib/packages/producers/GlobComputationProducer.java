// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.packages.producers;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.Globber;
import com.google.devtools.build.lib.skyframe.GlobDescriptor;
import com.google.devtools.build.lib.skyframe.IgnoredPackagePrefixesValue;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.StateMachine;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Serves as the entrance {@link StateMachine} for {@link
 * com.google.devtools.build.lib.skyframe.GlobFunctionWithMultipleRecursiveFunctions}.
 *
 * <p>Gets all ignored package prefix patterns. Starts the {@link FragmentProducer}s chain which
 * recursively process each glob pattern fragment. Accepts and aggregates globbing matching {@link
 * PathFragment}s result.
 */
public class GlobComputationProducer
    implements StateMachine, Consumer<SkyValue>, FragmentProducer.ResultSink {

  /**
   * Propagates all glob matching {@link PathFragment}s or any {@link Exception}.
   *
   * <p>If any {@link GlobError} is accepted, the already discovered path fragments are still
   * reported. However, {@link
   * com.google.devtools.build.lib.skyframe.GlobFunctionWithRecursionInSingleFunction} throws the
   * first discovered {@link GlobError} wrapped in a {@link
   * com.google.devtools.build.lib.skyframe.GlobFunction.GlobFunctionException}.
   *
   * <p>The already discovered path fragments should be considered as undefined. Since: (1) there is
   * no skyframe restart after glob computation throws an exception, so the discovered path
   * fragments can miss some matchings; (2) these discovered path fragments are not used to
   * construct a {@link com.google.devtools.build.lib.skyframe.GlobValue}.
   */
  public interface ResultSink {
    void acceptPathFragmentsWithoutPackageFragment(ImmutableSet<PathFragment> pathFragments);

    void acceptGlobError(GlobError error);
  }

  // -------------------- Input --------------------
  private final GlobDescriptor globDescriptor;
  private final ResultSink resultSink;

  // -------------------- Internal State --------------------
  private final ImmutableSet.Builder<PathFragment> pathFragmentsWithPackageFragment;
  private ImmutableSet<PathFragment> ignorePackagePrefixPatterns = null;
  private final ConcurrentHashMap<String, Pattern> regexPatternCache;

  public GlobComputationProducer(
      GlobDescriptor globDescriptor,
      ConcurrentHashMap<String, Pattern> regexPatternCache,
      ResultSink resultSink) {
    this.globDescriptor = globDescriptor;
    this.regexPatternCache = regexPatternCache;
    this.resultSink = resultSink;
    this.pathFragmentsWithPackageFragment = ImmutableSet.builder();
  }

  @Override
  public StateMachine step(Tasks tasks) throws InterruptedException {
    RepositoryName repositoryName = globDescriptor.getPackageId().getRepository();
    tasks.lookUp(IgnoredPackagePrefixesValue.key(repositoryName), (Consumer<SkyValue>) this);
    return this::createFragmentProducer;
  }

  @Override
  public void accept(SkyValue skyValue) {
    this.ignorePackagePrefixPatterns = ((IgnoredPackagePrefixesValue) skyValue).getPatterns();
  }

  private StateMachine createFragmentProducer(Tasks tasks) {
    Preconditions.checkNotNull(ignorePackagePrefixPatterns);
    ImmutableList<String> patterns =
        ImmutableList.copyOf(Splitter.on('/').split(globDescriptor.getPattern()));
    GlobDetail globDetail =
        GlobDetail.create(
            globDescriptor.getPackageId(),
            globDescriptor.getPackageRoot(),
            patterns,
            /* containsMultipleDoubleStars= */ Collections.frequency(patterns, "**") > 1,
            ignorePackagePrefixPatterns,
            regexPatternCache,
            globDescriptor.globberOperation());
    Set<Pair<PathFragment, Integer>> visitedGlobSubTasks = null;
    if (globDetail.containsMultipleDoubleStars()) {
      visitedGlobSubTasks = new HashSet<>();
    }
    tasks.enqueue(
        new FragmentProducer(
            globDetail,
            globDetail
                .packageIdentifier()
                .getPackageFragment()
                .getRelative(globDescriptor.getSubdir()),
            /* fragmentIndex= */ 0,
            visitedGlobSubTasks,
            (FragmentProducer.ResultSink) this));
    return this::buildAndBubbleUpResult;
  }

  @Override
  public void acceptPathFragmentWithPackageFragment(PathFragment pathFragment) {
    pathFragmentsWithPackageFragment.add(pathFragment);
  }

  @Override
  public void acceptGlobError(GlobError error) {
    resultSink.acceptGlobError(error);
  }

  /**
   * Removes the package fragment from all accepted matching path fragments before {@link
   * ResultSink} accepts them.
   */
  public StateMachine buildAndBubbleUpResult(Tasks tasks) {
    resultSink.acceptPathFragmentsWithoutPackageFragment(
        pathFragmentsWithPackageFragment.build().parallelStream()
            .map(p -> p.relativeTo(globDescriptor.getPackageId().getPackageFragment()))
            .collect(toImmutableSet()));
    return DONE;
  }

  /**
   * Container which holds all constant information needed for globbing.
   *
   * <p>This object is created and passed into {@link FragmentProducer} so that we only need one
   * reference of {@link GlobDetail} downstream.
   */
  @AutoValue
  abstract static class GlobDetail {
    static GlobDetail create(
        PackageIdentifier packageIdentifier,
        Root packageRoot,
        ImmutableList<String> patternFragments,
        boolean containsMultipleDoubleStars,
        ImmutableSet<PathFragment> ignoredPackagePrefixesPatterns,
        ConcurrentHashMap<String, Pattern> regexPatternCache,
        Globber.Operation globOperation) {
      return new AutoValue_GlobComputationProducer_GlobDetail(
          packageIdentifier,
          packageRoot,
          patternFragments,
          containsMultipleDoubleStars,
          ignoredPackagePrefixesPatterns,
          regexPatternCache,
          globOperation);
    }

    abstract PackageIdentifier packageIdentifier();

    abstract Root packageRoot();

    abstract ImmutableList<String> patternFragments();

    /**
     * When multiple {@code **}s appear in pattern fragments, a set is created to track visited glob
     * subtasks in order to prevent duplicate work.
     *
     * <p>See {@link FragmentProducer#visitedGlobSubTasks} for more details.
     */
    abstract boolean containsMultipleDoubleStars();

    abstract ImmutableSet<PathFragment> ignoredPackagePrefixesPatterns();

    abstract ConcurrentHashMap<String, Pattern> regexPatternCache();

    abstract Globber.Operation globOperation();
  }
}
