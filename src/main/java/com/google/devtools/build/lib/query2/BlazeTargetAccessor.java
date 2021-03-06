// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2;

import static com.google.devtools.build.lib.packages.BuildType.TRISTATE;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.AggregatingAttributeMapper;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ConstantRuleVisibility;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.PackageGroupsRuleVisibility;
import com.google.devtools.build.lib.packages.PackageSpecification;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetNotFoundException;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryVisibility;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Implementation of {@link TargetAccessor &lt;Target&gt;} that uses an
 * {@link AbstractBlazeQueryEnvironment &lt;Target&gt;} internally to report issues and resolve
 * targets.
 */
final class BlazeTargetAccessor implements TargetAccessor<Target> {
  private final AbstractBlazeQueryEnvironment<Target> queryEnvironment;

  BlazeTargetAccessor(AbstractBlazeQueryEnvironment<Target> queryEnvironment) {
    this.queryEnvironment = queryEnvironment;
  }

  @Override
  public String getTargetKind(Target target) {
    return target.getTargetKind();
  }

  @Override
  public String getLabel(Target target) {
    return target.getLabel().toString();
  }

  @Override
  public String getPackage(Target target) {
    return target.getPackage().getNameFragment().toString();
  }

  @Override
  public List<Target> getLabelListAttr(QueryExpression caller, Target target, String attrName,
      String errorMsgPrefix) throws QueryException {
    Preconditions.checkArgument(target instanceof Rule);

    List<Target> result = new ArrayList<>();
    Rule rule = (Rule) target;

    AggregatingAttributeMapper attrMap = AggregatingAttributeMapper.of(rule);
    Type<?> attrType = attrMap.getAttributeType(attrName);
    if (attrType == null) {
      // Return an empty list if the attribute isn't defined for this rule.
      return ImmutableList.of();
    }

    for (Label label : attrMap.getReachableLabels(attrName, false)) {
      try {
        result.add(queryEnvironment.getTarget(label));
      } catch (TargetNotFoundException e) {
        queryEnvironment.reportBuildFileError(caller, errorMsgPrefix + e.getMessage());
      }
    }

    return result;
  }

  @Override
  public List<String> getStringListAttr(Target target, String attrName) {
    Preconditions.checkArgument(target instanceof Rule);
    return NonconfigurableAttributeMapper.of((Rule) target).get(attrName, Type.STRING_LIST);
  }

  @Override
  public String getStringAttr(Target target, String attrName) {
    Preconditions.checkArgument(target instanceof Rule);
    return NonconfigurableAttributeMapper.of((Rule) target).get(attrName, Type.STRING);
  }

  @Override
  public Iterable<String> getAttrAsString(Target target, String attrName) {
    Preconditions.checkArgument(target instanceof Rule);
    List<String> values = new ArrayList<>(); // May hold null values.
    Attribute attribute = ((Rule) target).getAttributeDefinition(attrName);
    if (attribute != null) {
      Type<?> attributeType = attribute.getType();
      for (Object attrValue : AggregatingAttributeMapper.of((Rule) target).visitAttribute(
          attribute.getName(), attributeType)) {

        // Ugly hack to maintain backward 'attr' query compatibility for BOOLEAN and TRISTATE
        // attributes. These are internally stored as actual Boolean or TriState objects but were
        // historically queried as integers. To maintain compatibility, we inspect their actual
        // value and return the integer equivalent represented as a String. This code is the
        // opposite of the code in BooleanType and TriStateType respectively.
        if (attributeType == BOOLEAN) {
          values.add(Type.BOOLEAN.cast(attrValue) ? "1" : "0");
        } else if (attributeType == TRISTATE) {
            switch (BuildType.TRISTATE.cast(attrValue)) {
              case AUTO :
                values.add("-1");
                break;
              case NO :
                values.add("0");
                break;
              case YES :
                values.add("1");
                break;
              default :
                throw new AssertionError("This can't happen!");
            }
        } else {
          values.add(attrValue == null ? null : attrValue.toString());
        }
      }
    }
    return values;
  }

  @Override
  public boolean isRule(Target target) {
    return target instanceof Rule;
  }

  @Override
  public boolean isTestRule(Target target) {
    return TargetUtils.isTestRule(target);
  }

  @Override
  public boolean isTestSuite(Target target) {
    return TargetUtils.isTestSuiteRule(target);
  }

  @Override
  public Set<QueryVisibility<Target>> getVisibility(Target target) throws QueryException {
    ImmutableSet.Builder<QueryVisibility<Target>> result = ImmutableSet.builder();
    result.add(QueryVisibility.samePackage(target, this));
    convertVisibility(result, target);
    return result.build();
  }

  // CAUTION: keep in sync with ConfiguredTargetFactory#convertVisibility()
  private void convertVisibility(
      ImmutableSet.Builder<QueryVisibility<Target>> packageSpecifications,
      Target target)
      throws QueryException {
   RuleVisibility ruleVisibility = target.getVisibility();
   if (ruleVisibility instanceof ConstantRuleVisibility) {
     if (((ConstantRuleVisibility) ruleVisibility).isPubliclyVisible()) {
       packageSpecifications.add(QueryVisibility.<Target>everything());
     }
     return;
   } else if (ruleVisibility instanceof PackageGroupsRuleVisibility) {
     PackageGroupsRuleVisibility packageGroupsVisibility =
         (PackageGroupsRuleVisibility) ruleVisibility;
     for (Label groupLabel : packageGroupsVisibility.getPackageGroups()) {
       try {
         convertGroupVisibility((PackageGroup) queryEnvironment.getTarget(groupLabel),
             packageSpecifications);
       } catch (TargetNotFoundException e) {
         throw new QueryException(e.getMessage());
       }
     }
     for (PackageSpecification spec : packageGroupsVisibility.getDirectPackages()) {
       packageSpecifications.add(new BlazeQueryVisibility(spec));
     }
     return;
   } else {
     throw new IllegalStateException("unknown visibility: " + ruleVisibility.getClass());
   }
  }

  private void convertGroupVisibility(
      PackageGroup group, ImmutableSet.Builder<QueryVisibility<Target>> packageSpecifications)
      throws QueryException, TargetNotFoundException {
    for (Label include : group.getIncludes()) {
      convertGroupVisibility((PackageGroup) queryEnvironment.getTarget(include),
          packageSpecifications);
    }
    for (PackageSpecification spec : group.getPackageSpecifications()) {
      packageSpecifications.add(new BlazeQueryVisibility(spec));
    }
  }
}
