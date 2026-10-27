from sys import exit as terminate_process
from .cli import main as run_rea_entrypoint

if __name__ == "__main__":
    terminate_process(run_rea_entrypoint())
