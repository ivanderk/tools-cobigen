@import "bower_components/bootstrap/less/bootstrap";
@import "main/css/variables";
@import "main/css/mixins";

body {
  padding-top: 50px;
  padding-bottom: 20px;
}

.table-striped {
  tbody {
    > tr.active {
      &:hover {
        > td {
          background-color: darken(@brand-info, 10%)
        }
      }
      > td {
        background-color: @brand-info;
      }
    }
  }
  & * {
    .user-select(none);
  }
}

[ng\:cloak], [ng-cloak], [data-ng-cloak], [x-ng-cloak], .ng-cloak, .x-ng-cloak {
  display: none !important;
}

.row {
  margin-bottom: 20px;
}

.row .row {
  margin-top: 10px;
  margin-bottom: 0;
}