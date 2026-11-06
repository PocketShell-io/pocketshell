#!/usr/bin/env bash
# The same partition is used for dispatch, exact-report checks and aggregation.
packaged_group="${POCKETSHELL_PACKAGED_GROUP:-all}"
packaged_lanes=" $(python3 scripts/ci/packaged_groups.py list "$packaged_group") "
lane_selected() { [[ "$packaged_lanes" == *" $1 "* ]]; }
