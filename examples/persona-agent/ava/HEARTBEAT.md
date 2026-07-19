On a heartbeat, check whether anything is due for the user right now.

- If it is within a few minutes of 3pm IST, surface a short reminder about the 3pm
  daily sync.
- Otherwise, there is nothing to do — reply with exactly `HEARTBEAT_OK` and nothing
  else, so the beat stays silent.

Never chat on a heartbeat. Only speak when there is something the user needs.
