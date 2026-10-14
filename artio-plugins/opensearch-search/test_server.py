import importlib.util
import json
import pathlib
import unittest
from unittest.mock import patch


ROOT = pathlib.Path(__file__).parent
SPEC = importlib.util.spec_from_file_location("opensearch_plugin", ROOT / "server.py")
PLUGIN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PLUGIN)


class OpenSearchPluginTests(unittest.TestCase):
    def test_validates_and_indexes_document_with_product_and_locale_identity(self):
        document = {"productId": "00000000-0000-0000-0000-000000000001", "sku": "SKU-1",
                    "name": "Lamp", "description": "Desk light", "category": None,
                    "brand": None, "locale": "de"}
        with patch.object(PLUGIN, "request", side_effect=[PLUGIN.PluginError(404, "missing"), {}, {}, {"result": "created"}]) as call:
            result = PLUGIN.index_document({"document": document, "active": True})
        self.assertEqual({"indexed": True}, result)
        self.assertEqual("/_alias/artio-products", call.call_args_list[0].args[1])
        self.assertEqual("PUT", call.call_args_list[1].args[0])
        method, path, indexed = call.call_args_list[3].args
        self.assertEqual("PUT", method)
        self.assertIn("/artio-products/_doc/00000000-0000-0000-0000-000000000001-de", path)
        self.assertTrue(indexed["active"])

    def test_rejects_invalid_product_id_and_unbounded_search(self):
        with self.assertRaises(PLUGIN.PluginError) as invalid_id:
            PLUGIN.validate_document({"document": {"productId": "x"}})
        self.assertEqual("invalid_product_id", invalid_id.exception.code)
        with self.assertRaises(PLUGIN.PluginError) as invalid_size:
            PLUGIN.search_products({"request": {"query": "x", "locale": "en", "page": 0, "size": 101}})
        self.assertEqual("invalid_search_bounds", invalid_size.exception.code)

    def test_search_falls_back_from_requested_locale_to_english(self):
        responses = [{"hits": {"hits": []}}, {"hits": {"hits": [
            {"_source": {"productId": "00000000-0000-0000-0000-000000000001"}}
        ]}}]
        with patch.object(PLUGIN, "request", side_effect=responses) as call:
            result = PLUGIN.search_products({"request": {"query": "lamp", "locale": "fr", "page": 0, "size": 5}})
        self.assertEqual(["00000000-0000-0000-0000-000000000001"], result["productIds"])
        self.assertEqual("fr", call.call_args_list[0].args[2]["query"]["bool"]["filter"][0]["term"]["locale"])
        self.assertEqual("en", call.call_args_list[1].args[2]["query"]["bool"]["filter"][0]["term"]["locale"])

    def test_rebuild_switches_alias_only_after_generation_refresh(self):
        generation = "artio-products-generation-" + "a" * 32
        with patch.object(PLUGIN, "request", side_effect=[{}, {
                "old-index": {"aliases": {"artio-products": {}}}}, {}, {}, {}]) as call:
            self.assertEqual({"committed": True}, PLUGIN.commit_rebuild({"generation": generation}))
        self.assertEqual(f"/{generation}/_refresh", call.call_args_list[0].args[1])
        self.assertEqual("/_aliases", call.call_args_list[2].args[1])
        actions = call.call_args_list[2].args[2]["actions"]
        self.assertEqual("old-index", actions[0]["remove"]["index"])
        self.assertEqual(generation, actions[1]["add"]["index"])
        self.assertEqual("DELETE", call.call_args_list[3].args[0])

    def test_manifest_is_a_noninstallable_two_image_bundle_until_release(self):
        manifest = json.loads((ROOT / "manifest.template.json").read_text(encoding="utf-8"))
        self.assertEqual("catalog.opensearch", manifest["id"])
        self.assertEqual(["catalog.search.v1"], manifest["extensionPoints"])
        self.assertEqual(["catalog.read"], manifest["permissions"])
        self.assertEqual(["opensearch"], [service["id"] for service in manifest["services"]])
        self.assertEqual(["/usr/share/opensearch/data"], manifest["services"][0]["persistentPaths"])
        self.assertEqual(768, manifest["services"][0]["memoryLimitMb"])
        opensearch_config = (ROOT / "opensearch" / "opensearch.yml").read_text(encoding="utf-8")
        self.assertIn("plugins.security.disabled: true", opensearch_config)
        self.assertIn("path.logs: /tmp/opensearch-logs", opensearch_config)
        self.assertIn("REPLACE_WITH_PUBLISHED_IMAGE_DIGEST", manifest["image"])


if __name__ == "__main__":
    unittest.main()
