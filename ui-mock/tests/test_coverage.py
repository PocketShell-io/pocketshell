"""Coverage inventory tests. Real Destinations.kt parsing; synthetic render cases."""
from __future__ import annotations

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from catalog import Case
from coverage import Destination, attribute, build_inventory, parse_destinations, report, words

REPO = Path(__file__).resolve().parents[2]
DESTINATIONS_KT = REPO / "app2/src/main/java/com/pocketshell/next/nav/Destinations.kt"


def case(class_name, method, label):
    return Case(id=label, module="app2", class_name=f"com.pocketshell.next.render.{class_name}",
                method=method, label=label, source=f"{class_name}.kt", kind="screen-fixture")


def real_destinations():
    return parse_destinations(DESTINATIONS_KT.read_text(encoding="utf-8"))


class DestinationParsingTests(unittest.TestCase):
    def test_real_graph_count_and_graph_order(self):
        destinations = real_destinations()
        # 28 since #2814 hard-cut VoiceLanguage + GraceSettings (was 30).
        self.assertEqual(len(destinations), 28)
        self.assertEqual([d.name for d in destinations[:5]],
                         ["Hosts", "Workspaces", "Workspace", "Session", "Files"])
        self.assertEqual(destinations[-1].name, "WorkspaceRootAction")

    def test_every_destination_resolves_its_route_pattern(self):
        for destination in real_destinations():
            self.assertTrue(destination.route, destination.name)

    def test_missing_all_list_rejected(self):
        with self.assertRaisesRegex(ValueError, "all"):
            parse_destinations("package com.pocketshell.next.nav\nsealed class Destination")

    def test_words_split_camel_kebab_and_snake(self):
        self.assertEqual(words("populatedWorkspaceDetail-font_scale2"),
                         ["populated", "workspace", "detail", "font", "scale2"])


class AttributeTests(unittest.TestCase):
    def test_covered_gap_and_specificity(self):
        destinations = [Destination("Hosts", "hosts"), Destination("HostForm", "host-form"),
                        Destination("Files", "files/{hostId}")]
        cases = [case("HostScreenRenders", "hostListEmpty", "quiet-360-hosts-empty"),
                 case("HostScreenRenders", "hostFormAdd", "quiet-360-host-form-add")]
        claimed, unclaimed, inherited = attribute(destinations, cases)
        self.assertEqual([c.method for c in claimed["Hosts"]], ["hostListEmpty"])
        # hostForm* must land on HostForm, not the generic Hosts plural stem.
        self.assertEqual([c.method for c in claimed["HostForm"]], ["hostFormAdd"])
        self.assertEqual(claimed["Files"], [])
        self.assertEqual(unclaimed, [])
        self.assertEqual(inherited, [])

    def test_alias_and_same_screen_hand_map(self):
        destinations = real_destinations()
        cases = [case("ServicesScreenRenders", "servicesActive", "services-active"),
                 case("SessionTreeScreenRenders", "sessionTreeHeaderBackAndUsage",
                      "i2532-session-tree-header"),
                 case("UsageScreenRenders", "usageScreenCollapsed", "usage-screen-collapsed")]
        claimed, unclaimed, inherited = attribute(destinations, cases)
        self.assertEqual([c.method for c in claimed["Ports"]], ["servicesActive"])
        self.assertEqual([c.method for c in claimed["Workspace"]],
                         ["sessionTreeHeaderBackAndUsage"])
        # SessionTree is the workspace detail; it must not cover Session.
        self.assertEqual(claimed["Session"], [])
        self.assertEqual(claimed["Hosts"], [])
        self.assertEqual([c.method for c in claimed["Usage"]], ["usageScreenCollapsed"])
        self.assertEqual(inherited, ["HostUsage"])
        self.assertEqual([c.method for c in claimed["HostUsage"]], ["usageScreenCollapsed"])

    def test_cases_matching_no_destination_are_reported_unclaimed(self):
        destinations = real_destinations()
        claimed, unclaimed, inherited = attribute(
            destinations, [case("ComposerRenders", "composerEmpty", "p1-composer-empty")])
        self.assertEqual([c.method for c in unclaimed], ["composerEmpty"])
        self.assertEqual(list(claimed.values()), [[] for _ in destinations])
        self.assertEqual(inherited, [])

    def test_share_flow_never_claims_hosts_via_its_no_hosts_label(self):
        destinations = real_destinations()
        claimed, unclaimed, _ = attribute(
            destinations, [case("ShareScreenRenders", "shareWithNoHosts", "p9-share-no-hosts")])
        self.assertEqual(claimed["Hosts"], [])
        self.assertEqual([c.method for c in unclaimed], ["shareWithNoHosts"])


class ReportTests(unittest.TestCase):
    def test_report_lists_every_destination_with_summary_and_gap(self):
        destinations = [Destination("Alpha", "alpha"), Destination("Beta", "beta")]
        cases = [case("AlphaScreenRenders", "alphaEmpty", "alpha-empty")]
        claimed, unclaimed, inherited = attribute(destinations, cases)
        text = report(destinations, claimed, unclaimed, inherited)
        self.assertIn("destination render coverage: 1/2 destinations covered", text)
        for destination in destinations:
            self.assertTrue(any(line.startswith(destination.name) for line in
                                text.splitlines()), destination.name)
        self.assertIn("GAP", text)

    def test_report_lists_unclaimed_and_inherited_sections(self):
        destinations = real_destinations()
        cases = [case("ComposerRenders", "composerEmpty", "p1-composer-empty"),
                 case("UsageScreenRenders", "usageScreenCollapsed", "usage-screen-collapsed")]
        claimed, unclaimed, inherited = attribute(destinations, cases)
        text = report(destinations, claimed, unclaimed, inherited)
        self.assertIn("render cases with no navigation destination (1): "
                      "ComposerRenders.composerEmpty", text)
        self.assertIn("same-screen coverage (inherited): HostUsage = Usage", text)


class InventoryTests(unittest.TestCase):
    """The served-catalog payload: every destination present, gaps explicit."""

    def setUp(self):
        self.cases = [case("ServicesScreenRenders", "servicesActive", "services-active"),
                      case("UsageScreenRenders", "usageScreenCollapsed", "usage-screen-collapsed"),
                      case("ComposerRenders", "composerEmpty", "p1-composer-empty")]
        self.data, self.error = build_inventory(REPO, self.cases)

    def test_every_real_destination_appears_exactly_once(self):
        self.assertEqual(self.error, "")
        names = [d["name"] for d in self.data["destinations"]]
        self.assertEqual(len(names), 28)
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(names[:5], ["Hosts", "Workspaces", "Workspace", "Session", "Files"])
        gaps = [d["name"] for d in self.data["destinations"] if d["gap"]]
        self.assertEqual(self.data["total"] - len(gaps), self.data["covered"])
        self.assertIn("Files", gaps)

    def test_covered_rows_carry_case_references_and_routes(self):
        by_name = {d["name"]: d for d in self.data["destinations"]}
        self.assertFalse(by_name["Ports"]["gap"])
        self.assertEqual(by_name["Ports"]["cases"],
                         [{"id": self.cases[0].id, "name": "ServicesScreenRenders.servicesActive"}])
        self.assertEqual(by_name["Files"],
                         {"name": "Files", "route": "files/{$ARG_HOST_ID}?$ARG_PATH={$ARG_PATH}",
                          "gap": True, "cases": []})
        self.assertTrue(all(d["route"] for d in self.data["destinations"]))

    def test_unclaimed_and_inherited_reported(self):
        self.assertEqual([u["name"] for u in self.data["unclaimed"]],
                         ["ComposerRenders.composerEmpty"])
        self.assertEqual(self.data["unclaimed"][0]["id"], self.cases[2].id)
        self.assertEqual(self.data["inherited"], ["HostUsage"])
        self.assertEqual(self.data["kit_examples"], 0)

    def test_ui_kit_examples_counted_but_never_attribute(self):
        kit = Case(id="kit1", module="shared:ui-kit",
                   class_name="com.pocketshell.uikit.render.DesignRenders",
                   method="buttonPrimary", label="hosts-button-primary",
                   source="DesignRenders.kt", kind="ui-kit-example")
        data, error = build_inventory(REPO, [kit])
        self.assertEqual(error, "")
        self.assertEqual(data["kit_examples"], 1)
        self.assertEqual(data["covered"], 0)
        self.assertTrue(all(d["gap"] for d in data["destinations"]))
        self.assertEqual(data["unclaimed"], [])

    def test_missing_destinations_file_degrades_explicitly(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            data, error = build_inventory(Path(tmp), self.cases)
        self.assertIsNone(data)
        self.assertIn("coverage unavailable", error)
        self.assertIn("Destinations.kt", error)

    def test_unparseable_graph_degrades_explicitly(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "app2/src/main/java/com/pocketshell/next/nav"
            target.mkdir(parents=True)
            (target / "Destinations.kt").write_text(
                "package com.pocketshell.next.nav\nsealed class Destination")
            data, error = build_inventory(Path(tmp), self.cases)
        self.assertIsNone(data)
        self.assertIn("coverage unavailable", error)


if __name__ == "__main__":
    unittest.main()
