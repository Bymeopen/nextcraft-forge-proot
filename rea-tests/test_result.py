import json

from rea.result import E_FILE_NOT_FOUND, ReaError, Result, failure, to_json_line


def test_json_line_is_single_line_with_schema():
    line = to_json_line(Result(command="doctor", data={"text": "baris\nbaru"}, mode="static-only"))
    assert "\n" not in line
    parsed = json.loads(line)
    assert parsed["schema"] == 1 and parsed["ok"] is True and parsed["mode"] == "static-only"
    assert parsed["data"]["text"] == "baris\nbaru"


def test_failure_carries_code_message_and_hint():
    parsed = json.loads(to_json_line(failure("id", ReaError(E_FILE_NOT_FOUND, "tidak ada", "periksa jalur"))))
    assert parsed["ok"] is False
    assert parsed["error"] == {"code": "file_not_found", "message": "tidak ada", "hint": "periksa jalur"}
