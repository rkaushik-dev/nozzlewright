"""Let the tool be run as a module.

``python -m nozzlewright.cli`` does the same thing as the ``nozzlewright`` console script.
CI and a service unit both use the module form, because it works before the
package scripts directory is on the path.
"""

from __future__ import annotations

from nozzlewright.cli.main import main

if __name__ == "__main__":
    main()
