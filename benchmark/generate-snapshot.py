import argparse
import csv


def main():
    parser = argparse.ArgumentParser(description="Generate a synthetic HR snapshot CSV.")
    parser.add_argument("--rows", type=int, default=200_000)
    parser.add_argument("--output", default="sample-data/generated-200k.csv")
    arguments = parser.parse_args()

    if arguments.rows < 0:
        raise SystemExit("--rows cannot be negative")

    with open(arguments.output, "w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow(("employeeId", "sourceVersion", "name", "email", "phone",
                         "managerId", "officeCode", "shift", "active"))
        for employee_number in range(1, arguments.rows + 1):
            writer.writerow((
                f"EMP-{employee_number:06d}",
                1,
                f"Example Worker {employee_number}",
                f"worker-{employee_number}@example.invalid",
                f"+100{employee_number:08d}",
                "",
                "BLR01",
                "DAY",
                "true",
            ))

    print(f"Wrote {arguments.rows} synthetic rows to {arguments.output}")


if __name__ == "__main__":
    main()
