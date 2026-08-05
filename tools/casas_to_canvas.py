# /// script
# requires-python = ">=3.11"
# dependencies = ["polars"]
# ///
"""Convert a CASAS scripted-ADL event directory into the wide per-minute CSV Spade reads.

    uv run tools/casas_to_canvas.py <event_dir> <output.csv>

CASAS ships one event per row (date,time,sensor,message) with the activity encoded
in the filename as p<participant>.t<task>.csv. Spade wants one row per minute with
a column per sensor, so this buckets events by minute and snapshots sensor state at
the end of each bucket.
"""

import re
import sys
from pathlib import Path

import polars as pl

ACTIVITY = {1: "Phone_Call", 2: "Wash_hands", 3: "Cook", 4: "Eat", 5: "Clean"}

# D01 is a door sensor reporting OPEN/CLOSE. Spade's encoder only understands
# ON/PRESENT and OFF/ABSENT, so the door folds onto the presence vocabulary.
VALUE_MAP = {"OPEN": "PRESENT", "CLOSE": "ABSENT"}

# E01 carries START_INSTRUCT/STOP_INSTRUCT experiment markers, not sensor state.
CONTROL_SENSORS = {"E01", "asterisk"}
CONTROL_MESSAGES = {"START", "END", "START_INSTRUCT", "STOP_INSTRUCT", "asterisk"}

BINARY_ON = {"ON", "OFF"}
BINARY_PRESENT = {"PRESENT", "ABSENT"}

# Column groups in the order canvas.csv uses: motion, item, door, analog.
GROUP_ORDER = {"M": 0, "I": 1, "D": 2, "A": 3}

# A minute is summarised by whether the sensor showed activity in it, not by the
# state it happened to end on. A PIR that fires and clears inside the same minute
# still means somebody walked past, so idle is the resting reading and the active
# reading wins whenever it appears.
IDLE = {"M": "OFF", "I": "PRESENT", "D": "ABSENT", "A": "0.0"}
ACTIVE = {"M": "ON", "I": "ABSENT", "D": "PRESENT"}


def group_of(sensor: str) -> str:
    return "A" if sensor.startswith("AD") else sensor[0]


def is_float(value: str) -> bool:
    try:
        float(value)
        return True
    except ValueError:
        return False


def read_session(path: Path) -> pl.DataFrame:
    df = pl.read_csv(path, has_header=True, new_columns=["date", "time", "sensor", "message"])
    df = df.filter(
        ~pl.col("sensor").is_in(list(CONTROL_SENSORS))
        & ~pl.col("message").is_in(list(CONTROL_MESSAGES))
    )
    return df.with_columns(
        pl.col("message").replace(VALUE_MAP).alias("message"),
        pl.concat_str([pl.col("date"), pl.lit(" "), pl.col("time").str.slice(0, 8)])
        .str.to_datetime("%Y-%m-%d %H:%M:%S", strict=False)
        .alias("ts"),
    ).drop_nulls("ts")


def classify(messages: set[str]) -> str:
    if messages & BINARY_ON:
        return "0-binary-on"
    if messages & BINARY_PRESENT:
        return "1-binary-present"
    return "2-analog"


def session_key(path: Path) -> tuple[int, int]:
    m = re.search(r"p(\d+)\.t(\d+)", path.name)
    if not m:
        raise ValueError(f"unexpected filename: {path.name}")
    return int(m.group(1)), int(m.group(2))


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    event_dir, out_path = Path(sys.argv[1]), Path(sys.argv[2])

    paths = sorted(event_dir.glob("p*.t*.csv"), key=session_key)
    if not paths:
        sys.exit(f"no p<n>.t<n>.csv files under {event_dir}")

    sessions = {p: read_session(p) for p in paths}

    seen: set[str] = set()
    for df in sessions.values():
        seen.update(df["sensor"].to_list())
    sensors = sorted(seen, key=lambda s: (GROUP_ORDER[group_of(s)], s))

    rows = []
    for path in paths:
        df = sessions[path].sort("ts")
        if df.is_empty():
            continue
        activity = ACTIVITY[session_key(path)[1]]

        minutes: dict = {}
        for ts, sensor, message in zip(df["ts"], df["sensor"], df["message"]):
            minutes.setdefault(ts.replace(second=0), []).append((sensor, message))

        ordered = sorted(minutes)
        for i, minute in enumerate(ordered):
            state = {s: IDLE[group_of(s)] for s in sensors}
            for sensor, message in minutes[minute]:
                group = group_of(sensor)
                if group == "A":
                    state[sensor] = max(state[sensor], message, key=float)
                elif message == ACTIVE[group]:
                    state[sensor] = message
            rows.append(
                [minute.year, minute.month, minute.day, minute.hour, minute.minute]
                + [state[s] for s in sensors]
                # The last minute of a session is where the participant leaves.
                + [activity, "ABSENT" if i == len(ordered) - 1 else "PRESENT"]
            )

    with out_path.open("w") as out:
        for row in rows:
            out.write(",".join(str(v) for v in row) + "\n")

    print(f"{len(paths)} sessions, {len(sensors)} sensors, {len(rows)} rows -> {out_path}")
    print("sensor order: " + ", ".join(sensors))


if __name__ == "__main__":
    main()
