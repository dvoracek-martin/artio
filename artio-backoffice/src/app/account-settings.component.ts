import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, EventEmitter, Input, OnChanges, Output, SimpleChanges, ViewChild, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize, timeout } from 'rxjs';
import { currentBackofficeLocale } from './backoffice-locale';
import { accountSettingsText, type AccountSettingsTextKey } from './account-settings-locale';

@Component({selector:'artio-account-settings',standalone:true,imports:[CommonModule,FormsModule],template:`
  <dialog #dialog class="workspace-dialog account-dialog" aria-labelledby="account-settings-title" (close)="closed()">
    <div class="workspace-dialog-panel"><div class="panel-heading"><div><p class="eyebrow">{{t('profile')}}</p><h2 id="account-settings-title">{{t('title')}}</h2><p class="muted">{{t('intro')}}</p></div><button type="button" class="dialog-close-button" (click)="dialogRef?.nativeElement.close()" [attr.aria-label]="t('close')" [attr.title]="t('close')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button></div>
      @if(loading){<p class="muted loading-indicator" role="status" aria-live="polite">{{t('loading')}}</p>}@if(notice){<p class="notice" role="status">{{notice}}</p>}@if(error){<div class="notice error" role="alert"><span>{{error}}</span><button type="button" class="secondary" (click)="load()" [disabled]="loading">{{t('retry')}}</button></div>}
      @if(profileLoaded){<div class="account-settings-grid">
        <section class="panel account-profile-panel"><p class="eyebrow">{{t('profile')}}</p><form class="account-form account-profile-form" (ngSubmit)="saveProfile()"><div class="account-fields-grid"><label>{{t('firstName')}}<input [(ngModel)]="profile.firstName" name="profileFirstName" maxlength="255" required [disabled]="loading||busy"></label><label>{{t('lastName')}}<input [(ngModel)]="profile.lastName" name="profileLastName" maxlength="255" required [disabled]="loading||busy"></label><div class="account-readonly-field"><span>{{t('email')}}</span><strong>{{profile.email}}</strong><small>{{profile.emailVerified?t('emailVerified'):t('emailUnverified')}}</small></div></div><label class="account-full-field">{{t('currentPassword')}}<input [(ngModel)]="profilePassword" name="profilePassword" type="password" autocomplete="current-password" required [disabled]="busy"></label><button class="primary" [disabled]="busy||loading">{{busy?t('saving'):t('saveProfile')}}</button></form></section>
        <section class="panel"><p class="eyebrow">{{t('password')}}</p><form class="account-form" (ngSubmit)="changePassword()"><label>{{t('currentPassword')}}<input [(ngModel)]="passwords.currentPassword" name="passwordCurrent" type="password" autocomplete="current-password" required [disabled]="busy"></label><label>{{t('newPassword')}}<input [(ngModel)]="passwords.newPassword" name="passwordNew" type="password" minlength="8" maxlength="72" autocomplete="new-password" required [disabled]="busy"></label><label>{{t('confirmPassword')}}<input [(ngModel)]="passwords.confirmPassword" name="passwordConfirm" type="password" autocomplete="new-password" required [disabled]="busy"></label><button class="primary" [disabled]="busy">{{busy?t('saving'):t('changePassword')}}</button></form></section>
        <section class="panel"><p class="eyebrow">{{t('emailChange')}}</p><p class="muted">{{t('emailInstructions')}}</p><form class="account-form" (ngSubmit)="requestEmailChange()"><label>{{t('newEmail')}}<input [(ngModel)]="emailDraft.newEmail" name="emailNew" type="email" autocomplete="email" required [disabled]="busy"></label><label>{{t('currentPassword')}}<input [(ngModel)]="emailDraft.currentPassword" name="emailCurrentPassword" type="password" autocomplete="current-password" required [disabled]="busy"></label><button class="primary" [disabled]="busy">{{busy?t('saving'):t('requestEmailChange')}}</button></form>
          @if(developmentToken){<label>{{t('developerToken')}}<input [ngModel]="developmentToken" name="emailDevToken" readonly></label><button class="secondary" type="button" [disabled]="busy" (click)="confirmEmail()">{{t('confirmEmail')}}</button>}
        </section>
      </div>}
    </div>
  </dialog>
`})
export class AccountSettingsComponent implements OnChanges {
  @ViewChild('dialog') dialogRef?:ElementRef<HTMLDialogElement>;
  @Input() open=false;
  @Output() close=new EventEmitter<void>();
  @Output() passwordChanged=new EventEmitter<string>();
  private http=inject(HttpClient);
  profile:any={firstName:'',lastName:'',email:'',emailVerified:false};
  profileLoaded=false;
  profilePassword='';
  passwords={currentPassword:'',newPassword:'',confirmPassword:''};
  emailDraft={newEmail:'',currentPassword:''};
  developmentToken='';
  loading=false;busy=false;error='';notice='';
  ngOnChanges(changes:SimpleChanges):void{if(changes['open']){if(this.open){setTimeout(()=>{const dialog=this.dialogRef?.nativeElement;if(dialog&&!dialog.open)dialog.showModal()},0);this.load()}else if(this.dialogRef?.nativeElement.open)this.dialogRef.nativeElement.close()}}
  t(key:AccountSettingsTextKey):string{return accountSettingsText(currentBackofficeLocale(),key)}
  closed():void{this.close.emit();this.error='';this.notice=''}
  load():void{if(this.loading)return;this.loading=true;this.profileLoaded=false;this.error='';this.notice='';this.http.get<any>('/api/v1/users/me').pipe(timeout({first:15000}),finalize(()=>this.loading=false)).subscribe({next:r=>{this.profile={firstName:r.firstName??'',lastName:r.lastName??'',email:r.email??'',emailVerified:!!r.emailVerified};this.profileLoaded=true},error:()=>{this.error=this.t('loadFailure')}})}
  saveProfile():void{if(this.busy)return;this.busy=true;this.error='';this.notice='';this.http.put<any>('/api/v1/users/me/profile',{firstName:this.profile.firstName,lastName:this.profile.lastName,currentPassword:this.profilePassword}).subscribe({next:r=>{this.profile={...this.profile,...r};this.profilePassword='';this.busy=false;this.notice=this.t('profileSaved')},error:()=>{this.busy=false;this.error=this.t('profileFailure')}})}
  changePassword():void{if(this.busy)return;if(this.passwords.newPassword!==this.passwords.confirmPassword){this.error=this.t('passwordMismatch');return}this.busy=true;this.error='';this.notice='';this.http.put<any>('/api/v1/users/me/password',{currentPassword:this.passwords.currentPassword,newPassword:this.passwords.newPassword}).subscribe({next:r=>{this.busy=false;this.passwordChanged.emit(r.message??this.t('passwordChanged'))},error:()=>{this.busy=false;this.error=this.t('passwordFailure')}})}
  requestEmailChange():void{if(this.busy)return;this.busy=true;this.error='';this.notice='';this.developmentToken='';this.http.post<any>('/api/v1/users/me/email-change',this.emailDraft).subscribe({next:r=>{this.busy=false;this.emailDraft.currentPassword='';this.notice=r.message??this.t('emailRequested');this.developmentToken=r.developmentToken??''},error:()=>{this.busy=false;this.error=this.t('emailFailure')}})}
  confirmEmail():void{if(this.busy||!this.developmentToken)return;this.busy=true;this.error='';this.http.post<any>('/api/v1/auth/email-change/complete',{token:this.developmentToken}).subscribe({next:r=>{this.busy=false;this.developmentToken='';this.passwordChanged.emit(r.message??this.t('emailConfirmed'))},error:()=>{this.busy=false;this.error=this.t('confirmationFailure')}})}
}
