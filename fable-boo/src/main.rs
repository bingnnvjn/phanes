use std::env;

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("play") => std::process::exit(fable_boo::play::run()),
        Some("bench") => std::process::exit(fable_boo::bench::run()),
        Some("-h") | Some("--help") | None => {
            println!("fable-boo <play|bench>");
            std::process::exit(0);
        }
        Some(other) => {
            eprintln!("fable-boo: unknown command: {other}");
            std::process::exit(2);
        }
    }
}
