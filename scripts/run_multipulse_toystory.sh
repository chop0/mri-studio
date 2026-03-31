#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

REMOTE_HOST="${TOYSTORY_HOST:-toystory}"
REMOTE_DIR="${TOYSTORY_MRI_DIR:-~/mri}"

PYTHON_FILES=(
  "multipulse.py"
  "mri_fast.py"
  "mri_opt.py"
  "jax_lbfgs.py"
  "requirements.txt"
)

echo "Preparing ${REMOTE_HOST}:${REMOTE_DIR}"
ssh "${REMOTE_HOST}" "mkdir -p ${REMOTE_DIR}"

echo "Syncing Python files to ${REMOTE_HOST}:${REMOTE_DIR}"
rsync -az \
  "${PROJECT_DIR}/multipulse.py" \
  "${PROJECT_DIR}/mri_fast.py" \
  "${PROJECT_DIR}/mri_opt.py" \
  "${PROJECT_DIR}/jax_lbfgs.py" \
  "${PROJECT_DIR}/requirements.txt" \
  "${REMOTE_HOST}:${REMOTE_DIR}/"

echo "Running multipulse.py on ${REMOTE_HOST}"
ssh "${REMOTE_HOST}" "
  set -euo pipefail
  cd ${REMOTE_DIR}
  if [ -x .venv/bin/python ]; then
    REMOTE_PYTHON=.venv/bin/python
  else
    REMOTE_PYTHON=python3
  fi
  MPLBACKEND=Agg \"\${REMOTE_PYTHON}\" multipulse.py
"

echo "Copying bloch_data.json back to ${PROJECT_DIR}"
rsync -az \
  "${REMOTE_HOST}:${REMOTE_DIR}/bloch_data.json" \
  "${PROJECT_DIR}/bloch_data.json"

echo "Done"
