"""Run O6 queries against FileGridMeta and session-local fixtures on SQL Server 2022."""

import argparse
from math import isclose
from pathlib import Path
import re
from statistics import stdev
import subprocess


QUERIES = Path(__file__).resolve().parents[1]
SQL_FILES = sorted(QUERIES.glob("[0-9][0-9]_*.sql"))
TABLES = ("files", "meter_readings", "outage_records", "operation_metrics")


def sql_for(number, **parameters):
    sql = SQL_FILES[number - 1].read_text(encoding="utf-8")
    for name, value in parameters.items():
        sql, count = re.subn(rf"(DECLARE @{name}\b[^=]+?= )[^;]+;", rf"\g<1>{value};", sql)
        if count != 1:
            raise AssertionError(f"Could not set @{name} in {SQL_FILES[number - 1].name}")
    return sql


def timestamp(value):
    return f"'2026-11-{value}'"


def check(condition, message, rows):
    if not condition:
        raise AssertionError(f"{message}: {rows}")


def check_close(actual, expected):
    check(isclose(float(actual), expected, rel_tol=1e-6, abs_tol=1e-6),
          f"Expected approximately {expected}", actual)


def verify(sqlcmd):
    def execute(batch):
        result = subprocess.run(
            [*sqlcmd, "-d", "FileGridMeta", "-b", "-r1", "-h", "-1", "-W", "-w", "65535", "-s", "|"],
            input="SET NOCOUNT ON;\n" + batch + "\nGO\n",
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        if result.returncode or result.stderr.strip():
            raise RuntimeError(result.stderr.strip() or result.stdout.strip())
        return [line.strip().split("|") for line in result.stdout.splitlines() if line.strip()]

    def query(number, fixture=None, **parameters):
        sql = sql_for(number, **parameters)
        if fixture is None:
            return execute(sql)
        # Rewrite only the table references for fixtures, and let SQL Server copy the real column types.
        sql = re.sub(r"\bdbo\.(files|meter_readings|outage_records|operation_metrics)\b", r"#o6_\1", sql)
        return execute(setup + fixture + "\nGO\n" + sql)

    server = execute("""
        SELECT CONVERT(nvarchar(128), SERVERPROPERTY(N'ProductVersion')), compatibility_level
        FROM sys.databases WHERE name = DB_NAME();
    """)
    print(f"FileGridMeta on SQL Server {server[0][0]}, compatibility level {server[0][1]}", flush=True)
    for index, path in enumerate(SQL_FILES, start=1):
        rows = query(index)
        print(f"PASS read-only smoke test: {path.name} ({len(rows)} result rows)", flush=True)

    setup = "\n".join(f"SELECT TOP (0) * INTO #o6_{name} FROM dbo.{name};" for name in TABLES) + "\n"
    network = (Path(__file__).with_name("network_fixture.sql")).read_text(encoding="utf-8")
    metrics = (QUERIES.parent / "seed" / "o6_operation_metrics_fixture.sql").read_text(encoding="utf-8")

    empty_anomalies = query(1, "")
    check(empty_anomalies == [["0"]], "Expected an empty anomaly page and a zero total", empty_anomalies)
    for index in (2, 3):
        check(query(index, "") == [], "Expected no meter results for an empty dataset", index)
    empty_outages = query(4, "")
    check(len(empty_outages) == 3 and all(row[2:] == ["0", "0", "NULL", "NULL", "NULL"] for row in empty_outages),
          "Expected zero outage counts and NULL derived values", empty_outages)
    empty_comparison = query(5, "")
    check(len(empty_comparison) == 8 and all(row[5] == "0" and float(row[6]) == 0 and row[7:] == ["NULL"] * 5
                                             for row in empty_comparison),
          "Expected zero operations and NULL performance statistics", empty_comparison)
    empty_scores = query(6, "")
    check(len(empty_scores) == 12 and all(row[3:8] == ["NULL"] * 5 and row[9:] == ["0"] * 4 for row in empty_scores),
          "Expected 12 unmeasured storage cells", empty_scores)
    print("PASS empty datasets across all six queries", flush=True)

    reading_window = dict(from_utc=timestamp("14T00:00:00"), to_utc=timestamp("14T06:00:00"))
    *first, first_total = query(1, network, **reading_window, page_size=1)
    *second, second_total = query(1, network, **reading_window, page_size=1, page=2)
    check(len(first) == len(second) == 1 and [first[0][0], second[0][0]] == ["even", "spike"],
          "Expected two anomalies in stable page order", first + second)
    for row, baseline, median in (
        (first[0], [1] * 10 + [2] * 9 + [12], 1.5),
        (second[0], [1] * 19 + [12], 1.0),
    ):
        deviation = stdev(baseline)
        check(row[8] == "2", "Expected both pages to report two anomalies", row)
        for observed, expected in zip(row[3:8], (12, median, deviation, median + 3 * deviation,
                                                 (12 - median) / deviation)):
            check_close(observed, expected)
    past_end = query(1, network, **reading_window, page_size=1, page=3)
    check(first_total == second_total == ["2"] and past_end == [["2"]],
          "Expected total 2 for each page, including the out-of-range page", [first_total, second_total, past_end])
    print("PASS anomaly detection, sample standard deviation, and pagination (empty page total 2, empty dataset total 0)",
          flush=True)

    rows = query(2, network, as_of_utc=timestamp("14T06:00:00.0000001"))
    expected = [
        ["flat", "Banat", "04:15:00", "105", "04:15:00"],
        ["even", "Dobrogea", "04:45:00", "70", "04:50:00"],
        ["old", "Banat", "04:40:00", "70", "04:50:00"],
        ["spike", "Muntenia", "04:45:00", "70", "04:50:00"],
        ["over", "Banat", "05:00:00", "60", "05:00:00"],
    ]
    expected = [[meter, zone, f"2026-11-14 {reading}.0000000", minutes, f"2026-11-14 {received}.0000000"]
                for meter, zone, reading, minutes, received in expected]
    check(rows == expected,
          "Expected receipt-based inactivity, independent reading time and zone, and stable ties", rows)
    print("PASS silent meters (recent historical uploads and exact 60-minute receipt excluded, +100 ns included)",
          flush=True)

    rows = query(3, network, **reading_window)
    hours = {(row[0], int(row[1][11:13])): (int(row[2]), float(row[3])) for row in rows}
    expected = {(zone, hour): (4, total) for zone, totals in
                (("Muntenia", [4, 4, 4, 4, 15]), ("Dobrogea", [4, 4, 6, 8, 18]))
                for hour, total in enumerate(totals)}
    expected.update({("Banat", 4): (3, 11), ("Banat", 5): (2, 5)})
    check(hours == expected, "Expected confirmed readings grouped into UTC hour starts", rows)
    print("PASS hourly consumption totals and half-open bounds", flush=True)

    rows = query(4, network, from_utc=timestamp("13T08:00:00"), to_utc=timestamp("14T10:00:00"))
    ranking, by_hour = rows[:3], rows[3:]
    check([row[:4] + row[6:] for row in ranking] == [
        ["1", "Dobrogea", "2", "4", "8"], ["1", "Muntenia", "2", "4", "8"], ["2", "Banat", "0", "4", "NULL"],
    ], "Expected tied ranks, an earliest tied peak hour, and a zero-outage zone", ranking)
    for row in ranking[:2]:
        check_close(row[4], 50)
        check_close(row[5], 1.5)
    check(by_hour == [["Dobrogea", "8", "2"], ["Muntenia", "8", "1"], ["Muntenia", "9", "1"]],
          "Expected counts by UTC hour of day, including NULL station IDs", by_hour)
    print("PASS outage rankings and UTC hour-of-day counts", flush=True)

    run_windows = dict(normal_from_utc=timestamp("14T10:00:00"), normal_to_utc=timestamp("14T10:30:00"),
                       peak_from_utc=timestamp("14T11:00:00"), peak_to_utc=timestamp("14T11:30:00"))
    rows = query(5, metrics, **run_windows, peak_scenario="N'peak'")
    normal, peak = [row for row in rows if row[0] == "METER_READING"]
    for row, label, latencies, error_rate in (
        (normal, "normal", (20, 29, 29.8), 50),
        (peak, "peak", (50, 59, 59.8), 0),
    ):
        check(row[2] == label and row[5] == "2", "Expected two live operations in the selected run", row)
        for observed, expected in zip(row[6:11], (2 / 30, *latencies, error_rate)):
            check_close(observed, expected)
    check_close(peak[11], 100 * (59 - 29) / 29)
    outage_peak = next(row for row in rows if row[:2] == ["OUTAGE_RECORD", "peak"])
    check(outage_peak[11] == "NULL", "Expected NULL change from a zero baseline", outage_peak)
    storm_rows = query(5, metrics, **run_windows)
    storm = next(row for row in storm_rows if row[:2] == ["METER_READING", "peak"])
    check(storm[2] == "storm" and storm[5] == "1", "Expected the default storm scenario", storm)
    check_close(storm[8], 90)
    print("PASS normal versus peak and storm with synthetic C12 inputs", flush=True)

    rows = query(6, metrics, from_utc=timestamp("14T10:00:00"), to_utc=timestamp("14T11:20:00"))
    check(len(rows) == 12, "Expected the full storage matrix", rows)
    meter_rows = [row for row in rows if row[0] == "METER_READING"]
    check([row[1:2] + row[4:5] + row[9:] for row in meter_rows] == [
        ["FTP", "1", "2", "1", "1", "1"], ["S3", "2", "4", "1", "3", "1"], ["DB", "3", "3", "1", "2", "1"],
    ], "Expected ranks and separate sample counts for both data sources", meter_rows)
    for row, p95, failure, cost in zip(meter_rows, (575, 519, 453), (0, 0, 1 / 3), (1, 1.4, 3.2)):
        check_close(row[6], p95)
        check_close(row[7], 100 * failure)
        check_close(row[8], cost)
        check_close(row[3], round(50 * 454 / (p95 + 1) + 35 * (1 - failure) + 15 / cost, 3))
    outage = next(row for row in rows if row[:2] == ["OUTAGE_RECORD", "FTP"])
    check_close(outage[3], 100)
    print("PASS storage scores, documented costs, and ranking with synthetic C12 inputs", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sqlcmd", nargs=argparse.REMAINDER, help="sqlcmd executable and connection arguments after --")
    args = parser.parse_args()
    command = args.sqlcmd
    if command[:1] == ["--"]:
        command = command[1:]
    if not command:
        parser.error("supply a sqlcmd command after --")
    verify(command)
