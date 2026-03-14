import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-employees',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './employees.html',
  styleUrl: './employees.scss',
})
export class Employees {
  searchTerm = '';

  employees = [
    { name: 'Nguyen Van Anh', role: 'Operations Manager', dept: 'Operations', email: 'anh.nguyen@ntt.vn', status: 'active', joined: 'Jan 2023', initials: 'NA' },
    { name: 'Tran Thi Bich', role: 'Head Chef', dept: 'Kitchen', email: 'bich.tran@ntt.vn', status: 'active', joined: 'Mar 2022', initials: 'TB' },
    { name: 'Le Van Cuong', role: 'Inventory Clerk', dept: 'Warehouse', email: 'cuong.le@ntt.vn', status: 'active', joined: 'Jun 2023', initials: 'LC' },
    { name: 'Pham Thi Dung', role: 'Accountant', dept: 'Finance', email: 'dung.pham@ntt.vn', status: 'on_leave', joined: 'Feb 2021', initials: 'PD' },
    { name: 'Hoang Van Em', role: 'Sales Associate', dept: 'Sales', email: 'em.hoang@ntt.vn', status: 'active', joined: 'Sep 2023', initials: 'HE' },
    { name: 'Vo Thi Phuong', role: 'HR Specialist', dept: 'HR', email: 'phuong.vo@ntt.vn', status: 'active', joined: 'Apr 2022', initials: 'VP' },
  ];

  get filtered() {
    return this.employees.filter(e =>
      !this.searchTerm ||
      e.name.toLowerCase().includes(this.searchTerm.toLowerCase()) ||
      e.dept.toLowerCase().includes(this.searchTerm.toLowerCase()) ||
      e.role.toLowerCase().includes(this.searchTerm.toLowerCase())
    );
  }

  getStatusClass(s: string) {
    if (s === 'active') return 'badge-active';
    if (s === 'on_leave') return 'badge-pending';
    return 'badge-inactive';
  }

  getStatusLabel(s: string) {
    if (s === 'on_leave') return 'On Leave';
    return s.charAt(0).toUpperCase() + s.slice(1);
  }
}
