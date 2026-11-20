# Confirm the delivery note

Show the prepared `delivery.txt` to the user. Ask whether to proceed with verification and wait for an explicit answer.

Submit `decision: {"approved": true}` only after the user approves. A rejection uses `decision: {"approved": false}` and blocks advancement. The user may revise the note and approve later. Never manufacture confirmation from a script result or from the existence of an artifact.
