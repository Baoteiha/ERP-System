import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
})
export class Settings {
  permissions = ['View', 'Create', 'Edit', 'Delete', 'Approve', 'Export'];
  roles = [
    { name: 'Administrator', access: ['View', 'Create', 'Edit', 'Delete', 'Approve', 'Export'] },
    { name: 'Manager', access: ['View', 'Create', 'Edit', 'Approve', 'Export'] },
    { name: 'Operations Staff', access: ['View', 'Create', 'Edit'] },
    { name: 'Viewer', access: ['View'] },
  ];
}
