use anyhow::Result;
use csv::Reader;
use dbsp::{
    operator::{
        time_series::{RelOffset, RelRange},
        FilterMap,
    },
    CollectionHandle, IndexedZSet, OrdIndexedZSet, OutputHandle, RootCircuit,
};
use serde::Deserialize;
use size_of::SizeOf;
use time::Date;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Ord, PartialOrd, Hash, SizeOf)]
struct Record {
    location: String,
    date: Date,
    daily_vaccinations: Option<u64>,
}

fn build_circuit(
    circuit: &mut RootCircuit,
) -> Result<(
    CollectionHandle<Record, isize>,
    OutputHandle<OrdIndexedZSet<(String, i32, u8), (isize, isize), isize>>,
)> {
    let (input_stream, input_handle) = circuit.add_input_zset::<Record, isize>();
    let subset = input_stream.filter(|r| {
        r.location == "England"
            || r.location == "Northern Ireland"
            || r.location == "Scotland"
            || r.location == "Wales"
    });
    let monthly_totals = subset
        .index_with(|r| {
            (
                (r.location.clone(), r.date.year(), r.date.month() as u8),
                r.daily_vaccinations.unwrap_or(0),
            )
        })
        .aggregate_linear(|(_l, _y, _m), v| *v as isize);
    let moving_averages = monthly_totals
        .map_index(|((l, y, m), v)| (l.clone(), (*y as u32 * 12 + (*m as u32 - 1), *v)))
        .partitioned_rolling_average(RelRange::new(RelOffset::Before(2), RelOffset::Before(0)))
        .map_index(|(l, (date, avg))| {
            (
                (l.clone(), (date / 12) as i32, (date % 12 + 1) as u8),
                avg.unwrap(),
            )
        });
    let joined = monthly_totals.join_index(&moving_averages, |(l, y, m), cur, avg| {
        Some(((l.clone(), *y, *m), (*cur, *avg)))
    });
    Ok((input_handle, joined.output()))
}

fn main() -> Result<()> {
    let (circuit, (input_handle, output_handle)) = RootCircuit::build(build_circuit)?;

    let path = format!(
        "{}/examples/tutorial/vaccinations.csv",
        env!("CARGO_MANIFEST_DIR")
    );
    let mut input_records = Reader::from_path(path)?
        .deserialize()
        .map(|result| result.map(|record| (record, 1)))
        .collect::<Result<Vec<(Record, isize)>, _>>()?;
    input_handle.append(&mut input_records);

    circuit.step()?;

    output_handle
        .consolidate()
        .iter()
        .for_each(|((l, y, m), (cur, avg), w)| {
            println!("{l:16} {y}-{m:02} {cur:10} {avg:10}: {w:+}")
        });
    Ok(())
}