import { Building2, ChevronDown } from "lucide-react";
import type { Workspace } from "../api/contracts";

export function WorkspacePicker({
  workspaces,
  selectedId,
  onChange,
}: {
  workspaces: Workspace[];
  selectedId: string;
  onChange: (id: string) => void;
}) {
  return (
    <div className="workspace-picker">
      <Building2 size={18} aria-hidden="true" />
      <label className="sr-only" htmlFor="workspace">
        Workspace
      </label>
      <select
        id="workspace"
        value={selectedId}
        onChange={(event) => onChange(event.target.value)}
      >
        {workspaces.map((workspace) => (
          <option key={workspace.id} value={workspace.id}>
            {workspace.name}
          </option>
        ))}
      </select>
      <ChevronDown size={15} aria-hidden="true" />
    </div>
  );
}
